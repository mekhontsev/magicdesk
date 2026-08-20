package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;

final class AppWindowStateStore {
    private AppWindowStateStore() {
    }

    static AppWindowState load(final String stateKey) {
        if (!isSafeStateKey(stateKey)) {
            return null;
        }
        return DesktopStateStore.read(
                state -> state.appWindows.get(stateKey), null);
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
}
