package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DesktopContentStore {
    DesktopContentStore() {
    }

    boolean containsShortcut(final AppLaunchTarget target) {
        return DesktopStateStore.read(
                state -> state.content.shortcuts.contains(target), false);
    }

    List<AppLaunchTarget> shortcuts() {
        return DesktopStateStore.read(
                state -> new ArrayList<>(state.content.shortcuts),
                Collections.emptyList());
    }

    boolean addShortcut(final AppLaunchTarget target) {
        return target != null && DesktopStateStore.update(state -> {
            if (!state.content.shortcuts.contains(target)) {
                state.content.shortcuts.add(target);
            }
        });
    }

    boolean removeShortcut(final AppLaunchTarget target) {
        if (target == null) {
            return false;
        }
        final boolean[] removed = new boolean[1];
        return DesktopStateStore.update(state -> removed[0] =
                state.content.shortcuts.remove(target)) && removed[0];
    }

    AppLaunchTarget workspaceTarget() {
        return DesktopStateStore.read(
                state -> state.content.workspaceTarget, null);
    }

    boolean setWorkspaceTarget(final AppLaunchTarget target) {
        return DesktopStateStore.update(state ->
                state.content.workspaceTarget = target);
    }

    static final class State {
        final List<AppLaunchTarget> shortcuts = new ArrayList<>();
        AppLaunchTarget workspaceTarget;
    }
}
