package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;

final class AppWindowStateStore {
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

    private static final Object PENDING_MODE_LOCK = new Object();
    private static final Map<String, PendingModeUpdate> PENDING_MODES =
            new LinkedHashMap<>();
    private static long sPendingModeSequence;

    private AppWindowStateStore() {
    }

    static AppWindowState load(final String stateKey) {
        if (!isSafeStateKey(stateKey)) {
            return null;
        }
        final PendingModeUpdate pending;
        synchronized (PENDING_MODE_LOCK) {
            pending = PENDING_MODES.get(stateKey);
        }
        final AppWindowState stored = DesktopStateStore.read(
                state -> state.appWindows.get(stateKey), null);
        if (pending == null) {
            return stored;
        }
        return stored == null
                ? new AppWindowState(pending.mode, null)
                : stored.withMode(pending.mode);
    }

    static PendingModeUpdate beginModeUpdate(
            final String stateKey,
            final AppWindowState.Mode mode) {
        if (!isSafeStateKey(stateKey) || mode == null) {
            return null;
        }
        synchronized (PENDING_MODE_LOCK) {
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
        synchronized (PENDING_MODE_LOCK) {
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
        return DesktopStateStore.update(state -> state.appWindows.put(
                stateKey,
                new AppWindowState(AppWindowState.Mode.WINDOWED, bounds)));
    }

    static boolean isSafeStateKey(final String stateKey) {
        return PackageNameValidator.isSafe(stateKey)
                || BuiltInDesktopAppCatalog.isAppIdentityKey(stateKey);
    }

    static void clearPendingModeUpdatesForTests() {
        synchronized (PENDING_MODE_LOCK) {
            PENDING_MODES.clear();
            sPendingModeSequence = 0L;
        }
    }
}
