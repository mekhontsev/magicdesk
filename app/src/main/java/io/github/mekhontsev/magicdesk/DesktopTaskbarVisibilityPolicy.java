package io.github.mekhontsev.magicdesk;

final class DesktopTaskbarVisibilityPolicy {
    private DesktopTaskbarVisibilityPolicy() {
    }

    static boolean isVisible(
            final boolean localDisplay,
            final boolean hasActiveTask,
            final boolean activeTaskFreeform,
            final boolean desktopActive,
            final boolean previouslyVisible) {
        if (desktopActive || activeTaskFreeform) {
            return true;
        }
        if (hasActiveTask) {
            return false;
        }
        return localDisplay ? previouslyVisible : true;
    }
}
