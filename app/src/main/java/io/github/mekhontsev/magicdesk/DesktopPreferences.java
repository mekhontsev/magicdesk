package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class DesktopPreferences {
    static final int SYSTEM_DESKTOP_DPI = 0;
    static final int DEFAULT_DESKTOP_DPI = 192;

    private DesktopPreferences() {
    }

    static List<AppReference> taskbarApps() {
        return DesktopStateStore.read(state -> new ArrayList<>(state.taskbarApps),
                new ArrayList<>());
    }

    static void saveTaskbarApps(final Collection<AppReference> apps) {
        final List<AppReference> stored = new ArrayList<>();
        if (apps != null) {
            for (final AppReference app : apps) {
                if (app != null && !stored.contains(app)
                        && BuiltInDesktopAppCatalog.isPinnable(app.launchTarget())) {
                    stored.add(app);
                }
            }
        }
        DesktopStateStore.update(state -> {
            state.taskbarApps.clear();
            state.taskbarApps.addAll(stored);
        });
    }

}
