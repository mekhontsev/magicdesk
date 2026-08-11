package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;

final class AppWindowStateStore {
    private AppWindowStateStore() {
    }

    static AppWindowState load(final String packageName) {
        if (!PackageNameValidator.isSafe(packageName)) {
            return null;
        }
        return DesktopStateStore.read(
                state -> state.appWindows.get(packageName), null);
    }

    static boolean rememberMode(
            final String packageName,
            final AppWindowState.Mode mode) {
        if (!PackageNameValidator.isSafe(packageName) || mode == null) {
            return false;
        }
        return DesktopStateStore.update(state -> {
            final AppWindowState current =
                    state.appWindows.get(packageName);
            state.appWindows.put(
                    packageName,
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
            if (PackageNameValidator.isSafe(entry.getKey())
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
            final String packageName,
            final RelativeWindowBounds bounds) {
        if (!PackageNameValidator.isSafe(packageName) || bounds == null) {
            return false;
        }
        return DesktopStateStore.update(state -> state.appWindows.put(
                packageName,
                new AppWindowState(AppWindowState.Mode.WINDOWED, bounds)));
    }
}
