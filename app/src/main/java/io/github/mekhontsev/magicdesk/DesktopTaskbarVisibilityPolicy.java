package io.github.mekhontsev.magicdesk;

final class DesktopTaskbarVisibilityPolicy {
    private DesktopTaskbarVisibilityPolicy() {
    }

    static boolean isVisible(
            final boolean localDisplay,
            final boolean hasActiveTask,
            final boolean hasVisibleFreeformTask,
            final boolean hasVisibleFullscreenTask,
            final boolean desktopHostForeground,
            final boolean previouslyVisible) {
        // This is workspace policy visibility. The reveal controller still
        // applies the user's auto-hide setting to the rendered taskbar.
        if (desktopHostForeground || hasVisibleFreeformTask) {
            return true;
        }
        if (hasActiveTask || hasVisibleFullscreenTask) {
            return false;
        }
        return localDisplay ? previouslyVisible : true;
    }
}
