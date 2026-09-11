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
        final AppReference stateKey;
        final AppWindowState.Mode mode;
        final long sequence;

        private PendingModeUpdate(
                final AppReference stateKey,
                final AppWindowState.Mode mode,
                final long sequence) {
            this.stateKey = stateKey;
            this.mode = mode;
            this.sequence = sequence;
        }
    }

    private static final Object STATE_LOCK = new Object();
    private static final Object MODE_COMMIT_LOCK = new Object();
    private static final Map<AppReference, Long> COMMITTED_MODE_SEQUENCES =
            new LinkedHashMap<>();
    private static final Map<AppReference, PendingModeUpdate> PENDING_MODES =
            new LinkedHashMap<>();
    private static final Map<AppReference, SessionPatch> SESSION_PATCHES =
            new LinkedHashMap<>();
    private static long sPendingModeSequence;
    private static long sSessionPatchSequence;
    private static DesktopWorkspaceRuntime sSessionOwner;
    private static boolean sSessionPersistent = true;
    private static long sSessionStartSequence;

    private AppWindowStateStore() {
    }

    static AppWindowState load(final AppReference stateKey) {
        if (stateKey == null) {
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

    static void beginSession(
            final DesktopWorkspaceRuntime owner,
            final DesktopSessionPolicy policy) {
        if (owner == null || owner.isClosed()) {
            throw new IllegalArgumentException("an open workspace must own window state");
        }
        synchronized (STATE_LOCK) {
            final boolean persistent = policy == null || policy.persistWorkspace;
            if (sSessionOwner == owner && sSessionPersistent == persistent) {
                return;
            }
            sSessionOwner = owner;
            sSessionPersistent = persistent;
            sSessionStartSequence = sSessionPatchSequence;
        }
    }

    static boolean endSession(final DesktopWorkspaceRuntime owner) {
        while (true) {
            final Map<AppReference, SessionPatch> snapshot;
            synchronized (STATE_LOCK) {
                // Closing an old host must not flush or discard a newer session.
                if (owner == null || sSessionOwner != owner) {
                    return true;
                }
                if (!sSessionPersistent) {
                    SESSION_PATCHES.entrySet().removeIf(
                            entry -> entry.getValue().sequence
                                    > sSessionStartSequence);
                    sSessionOwner = null;
                    sSessionPersistent = true;
                    return true;
                }
                if (SESSION_PATCHES.isEmpty()) {
                    sSessionOwner = null;
                    sSessionPersistent = true;
                    return true;
                }
                snapshot = new LinkedHashMap<>(SESSION_PATCHES);
            }
            final boolean saved = DesktopStateStore.update(state -> {
                for (final Map.Entry<AppReference, SessionPatch> entry
                        : snapshot.entrySet()) {
                    final AppWindowState current =
                            state.appWindows.get(entry.getKey());
                    state.appWindows.put(
                            entry.getKey(), entry.getValue().apply(current));
                }
            });
            if (!saved) {
                synchronized (STATE_LOCK) {
                    if (sSessionOwner == owner) {
                        sSessionOwner = null;
                        sSessionPersistent = true;
                    }
                }
                return false;
            }
            synchronized (STATE_LOCK) {
                for (final Map.Entry<AppReference, SessionPatch> entry
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
            final AppReference stateKey,
            final AppWindowState.Mode mode) {
        if (stateKey == null || mode == null) {
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
        // Serialize persistence as well as sequence acceptance. A newer pending
        // choice is only an overlay; only a successful commit supersedes this one.
        synchronized (MODE_COMMIT_LOCK) {
            final Long committedSequence = COMMITTED_MODE_SEQUENCES.get(
                    update.stateKey);
            if (committedSequence != null
                    && committedSequence.longValue() >= update.sequence) {
                finishModeUpdate(update);
                return true;
            }
            final boolean committed = rememberMode(update.stateKey, update.mode);
            if (committed) {
                COMMITTED_MODE_SEQUENCES.put(update.stateKey, update.sequence);
            }
            finishModeUpdate(update);
            return committed;
        }
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
            final AppReference stateKey,
            final AppWindowState.Mode mode) {
        if (stateKey == null || mode == null) {
            return false;
        }
        synchronized (STATE_LOCK) {
            if (sSessionOwner != null) {
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
            final Map<AppReference, RelativeWindowBounds> boundsByApp) {
        if (boundsByApp == null || boundsByApp.isEmpty()) {
            return true;
        }
        final Map<AppReference, RelativeWindowBounds> snapshot =
                new LinkedHashMap<>();
        for (final Map.Entry<AppReference, RelativeWindowBounds> entry
                : boundsByApp.entrySet()) {
            if (entry.getKey() != null
                    && entry.getValue() != null) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        if (snapshot.isEmpty()) {
            return true;
        }
        synchronized (STATE_LOCK) {
            if (sSessionOwner != null) {
                for (final Map.Entry<AppReference, RelativeWindowBounds> entry
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
            for (final Map.Entry<AppReference, RelativeWindowBounds> entry
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
            final AppReference stateKey,
            final RelativeWindowBounds bounds) {
        if (stateKey == null || bounds == null) {
            return false;
        }
        synchronized (STATE_LOCK) {
            if (sSessionOwner != null) {
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



    static void clearPendingModeUpdatesForTests() {
        synchronized (MODE_COMMIT_LOCK) {
            COMMITTED_MODE_SEQUENCES.clear();
        }
        synchronized (STATE_LOCK) {
            PENDING_MODES.clear();
            SESSION_PATCHES.clear();
            sPendingModeSequence = 0L;
            sSessionPatchSequence = 0L;
            sSessionOwner = null;
            sSessionPersistent = true;
            sSessionStartSequence = 0L;
        }
    }
}
