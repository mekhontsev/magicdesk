package io.github.mekhontsev.magicdesk;

final class DesktopTaskbarVisibilityPolicy {
    private DesktopTaskbarVisibilityPolicy() {
    }

    static boolean isVisible(
            final boolean localDisplay,
            final boolean hasActiveTask,
            final boolean hasVisibleFreeformTask,
            final boolean desktopActive,
            final boolean previouslyVisible) {
        // This is workspace policy visibility. The reveal controller still
        // applies the user's auto-hide setting to the rendered taskbar.
        if (desktopActive || hasVisibleFreeformTask) {
            return true;
        }
        if (hasActiveTask) {
            return false;
        }
        return localDisplay ? previouslyVisible : true;
    }
}
