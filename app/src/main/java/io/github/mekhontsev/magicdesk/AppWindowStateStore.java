package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;

final class AppWindowStateStore {
    private static final class SessionPatch {
        final AppWindowState.Mode mode;
        final RelativeWindowBounds bounds;
        final boolean modeSet;
        final boolean boundsSet;
        final long sequence;

        SessionPatch(
                final AppWindowState.Mode mode,
                final RelativeWindowBounds bounds,
                final boolean modeSet,
                final boolean boundsSet,
                final long sequence) {
            this.mode = mode;
            this.bounds = bounds;
            this.modeSet = modeSet;
            this.boundsSet = boundsSet;
            this.sequence = sequence;
        }

        AppWindowState apply(final AppWindowState stored) {
            final AppWindowState.Mode resolvedMode = modeSet
                    ? mode : stored == null ? null : stored.mode;
            final RelativeWindowBounds resolvedBounds = boundsSet
                    ? bounds
                    : stored == null ? null : stored.windowBounds;
            return new AppWindowState(resolvedMode, resolvedBounds);
        }
    }

    static final class PendingModeUpdate {
        final String stateKey;
        final AppWindowState.Mode mode;
        final long sequence;

        private PendingModeUpdate(
                final String stateKey,
                final AppWindowState.Mode mode,
                final long sequence) {
            this.stateKey = stateKey;
            this.mode = mode;
            this.sequence = sequence;
        }
    }

    private static final Object STATE_LOCK = new Object();
    private static final Map<String, PendingModeUpdate> PENDING_MODES =
            new LinkedHashMap<>();
    private static final Map<String, SessionPatch> SESSION_PATCHES =
            new LinkedHashMap<>();
    private static long sPendingModeSequence;
    private static long sSessionPatchSequence;
    private static boolean sSessionActive;
    private static boolean sSessionPersistent = true;
    private static long sSessionStartSequence;

    private AppWindowStateStore() {
    }

    static AppWindowState load(final String stateKey) {
        if (!isSafeStateKey(stateKey)) {
            return null;
        }
        final PendingModeUpdate pending;
        final SessionPatch patch;
        synchronized (STATE_LOCK) {
            pending = PENDING_MODES.get(stateKey);
            patch = SESSION_PATCHES.get(stateKey);
        }
        AppWindowState resolved = DesktopStateStore.read(
                state -> state.appWindows.get(stateKey), null);
        if (patch != null) {
            resolved = patch.apply(resolved);
        }
        if (pending == null) {
            return resolved;
        }
        return resolved == null
                ? new AppWindowState(pending.mode, null)
                : resolved.withMode(pending.mode);
    }

    static void beginSession() {
        beginSession(DesktopSessionPolicy.USER, false);
    }

    static void beginSession(
            final DesktopSessionPolicy policy,
            final boolean replacingSameTask) {
        synchronized (STATE_LOCK) {
            if (sSessionActive && replacingSameTask) {
                return;
            }
            sSessionActive = true;
            sSessionPersistent = policy == null
                    || policy.persistWorkspace;
            sSessionStartSequence = sSessionPatchSequence;
        }
    }

    static boolean endSession() {
        while (true) {
            final Map<String, SessionPatch> snapshot;
            synchronized (STATE_LOCK) {
                if (!sSessionPersistent) {
                    SESSION_PATCHES.entrySet().removeIf(
                            entry -> entry.getValue().sequence
                                    > sSessionStartSequence);
                    sSessionActive = false;
                    sSessionPersistent = true;
                    return true;
                }
                if (SESSION_PATCHES.isEmpty()) {
                    sSessionActive = false;
                    sSessionPersistent = true;
                    return true;
                }
                snapshot = new LinkedHashMap<>(SESSION_PATCHES);
            }
            final boolean saved = DesktopStateStore.update(state -> {
                for (final Map.Entry<String, SessionPatch> entry
                        : snapshot.entrySet()) {
                    final AppWindowState current =
                            state.appWindows.get(entry.getKey());
                    state.appWindows.put(
                            entry.getKey(), entry.getValue().apply(current));
                }
            });
            if (!saved) {
                synchronized (STATE_LOCK) {
                    sSessionActive = false;
                    sSessionPersistent = true;
                }
                return false;
            }
            synchronized (STATE_LOCK) {
                for (final Map.Entry<String, SessionPatch> entry
                        : snapshot.entrySet()) {
                    final SessionPatch current =
                            SESSION_PATCHES.get(entry.getKey());
                    if (current != null
                            && current.sequence
                                    == entry.getValue().sequence) {
                        SESSION_PATCHES.remove(entry.getKey());
                    }
                }
            }
        }
    }

    static PendingModeUpdate beginModeUpdate(
            final String stateKey,
            final AppWindowState.Mode mode) {
        if (!isSafeStateKey(stateKey) || mode == null) {
            return null;
        }
        synchronized (STATE_LOCK) {
            final PendingModeUpdate update = new PendingModeUpdate(
                    stateKey, mode, ++sPendingModeSequence);
            PENDING_MODES.put(stateKey, update);
            return update;
        }
    }

    static boolean commitModeUpdate(final PendingModeUpdate update) {
        if (update == null) {
            return false;
        }
        final boolean committed = rememberMode(update.stateKey, update.mode);
        finishModeUpdate(update);
        return committed;
    }

    static void cancelModeUpdate(final PendingModeUpdate update) {
        finishModeUpdate(update);
    }

    private static void finishModeUpdate(final PendingModeUpdate update) {
        if (update == null) {
            return;
        }
        synchronized (STATE_LOCK) {
            final PendingModeUpdate current = PENDING_MODES.get(
                    update.stateKey);
            if (current != null && current.sequence == update.sequence) {
                PENDING_MODES.remove(update.stateKey);
            }
        }
    }

    static boolean rememberMode(
            final String stateKey,
            final AppWindowState.Mode mode) {
        if (!isSafeStateKey(stateKey) || mode == null) {
            return false;
        }
        synchronized (STATE_LOCK) {
            if (sSessionActive) {
                final SessionPatch current = SESSION_PATCHES.get(stateKey);
                SESSION_PATCHES.put(
                        stateKey,
                        new SessionPatch(
                                mode,
                                current == null ? null : current.bounds,
                                true,
                                current != null && current.boundsSet,
                                ++sSessionPatchSequence));
                return true;
            }
        }
        return DesktopStateStore.update(state -> {
            final AppWindowState current =
                    state.appWindows.get(stateKey);
            state.appWindows.put(
                    stateKey,
                    current == null
                            ? new AppWindowState(mode, null)
                            : current.withMode(mode));
        });
    }

    static boolean rememberWindowBounds(
            final Map<String, RelativeWindowBounds> boundsByPackage) {
        if (boundsByPackage == null || boundsByPackage.isEmpty()) {
            return true;
        }
        final Map<String, RelativeWindowBounds> snapshot =
                new LinkedHashMap<>();
        for (final Map.Entry<String, RelativeWindowBounds> entry
                : boundsByPackage.entrySet()) {
            if (isSafeStateKey(entry.getKey())
                    && entry.getValue() != null) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        if (snapshot.isEmpty()) {
            return true;
        }
        synchronized (STATE_LOCK) {
            if (sSessionActive) {
                for (final Map.Entry<String, RelativeWindowBounds> entry
                        : snapshot.entrySet()) {
                    final SessionPatch current =
                            SESSION_PATCHES.get(entry.getKey());
                    SESSION_PATCHES.put(
                            entry.getKey(),
                            new SessionPatch(
                                    current == null ? null : current.mode,
                                    entry.getValue(),
                                    current != null && current.modeSet,
                                    true,
                                    ++sSessionPatchSequence));
                }
                return true;
            }
        }
        return DesktopStateStore.update(state -> {
            for (final Map.Entry<String, RelativeWindowBounds> entry
                    : snapshot.entrySet()) {
                final AppWindowState current =
                        state.appWindows.get(entry.getKey());
                state.appWindows.put(
                        entry.getKey(),
                        current == null
                                ? new AppWindowState(
                                        null,
                                        entry.getValue())
                                : current.withWindowBounds(entry.getValue()));
            }
        });
    }

    static boolean rememberWindowed(
            final String stateKey,
            final RelativeWindowBounds bounds) {
        if (!isSafeStateKey(stateKey) || bounds == null) {
            return false;
        }
        synchronized (STATE_LOCK) {
            if (sSessionActive) {
                SESSION_PATCHES.put(
                        stateKey,
                        new SessionPatch(
                                AppWindowState.Mode.WINDOWED,
                                bounds,
                                true,
                                true,
                                ++sSessionPatchSequence));
                return true;
            }
        }
        return DesktopStateStore.update(state -> state.appWindows.put(
                stateKey,
                new AppWindowState(AppWindowState.Mode.WINDOWED, bounds)));
    }

    static boolean isSafeStateKey(final String stateKey) {
        return PackageNameValidator.isSafe(stateKey)
                || BuiltInDesktopAppCatalog.isAppIdentityKey(stateKey);
    }

    static void clearPendingModeUpdatesForTests() {
        synchronized (STATE_LOCK) {
            PENDING_MODES.clear();
            SESSION_PATCHES.clear();
            sPendingModeSequence = 0L;
            sSessionPatchSequence = 0L;
            sSessionActive = false;
            sSessionPersistent = true;
            sSessionStartSequence = 0L;
        }
    }
}
