package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DesktopContentStore {
    DesktopContentStore() {
    }

    State get() {
        return DesktopStateStore.get().content;
    }

    void save() {
        DesktopStateStore.save();
    }

    static final class State {
        final List<AppLaunchTarget> shortcuts = new ArrayList<>();
        AppLaunchTarget workspaceTarget;

        List<AppLaunchTarget> shortcutsView() {
            return Collections.unmodifiableList(shortcuts);
        }
    }
}
