package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Defines behavior that must stay consistent across desktop displays. */
final class DesktopScreenPolicy {
    enum WorkspaceAction {
        START_EXTERNAL_DESKTOP,
        FOCUS_DESKTOP,
        RESTORE_WINDOWS
    }

    private DesktopScreenPolicy() {
    }

    static WorkspaceAction workspaceAction(
            final int activeDisplayId,
            final Boolean hasVisibleAppTask) {
        if (activeDisplayId < 0) {
            return WorkspaceAction.START_EXTERNAL_DESKTOP;
        }
        return Boolean.FALSE.equals(hasVisibleAppTask)
                ? WorkspaceAction.RESTORE_WINDOWS
                : WorkspaceAction.FOCUS_DESKTOP;
    }

    static boolean isExternalDesktopSession(
            final int desktopDisplayId,
            final int activeDesktopDisplayId,
            final boolean consoleModeActive) {
        return isExternalDesktop(desktopDisplayId)
                && (consoleModeActive
                        || activeDesktopDisplayId == desktopDisplayId);
    }

    static boolean isExternalDesktop(final int desktopDisplayId) {
        return desktopDisplayId > Display.DEFAULT_DISPLAY;
    }

    static boolean canControlPhoneScreen(
            final boolean externalDesktopSession,
            final DesktopDisplayTarget target,
            final boolean shellReady,
            final boolean platformControlAvailable) {
        return shellReady
                && platformControlAvailable
                && externalDesktopSession
                && target != null
                && DesktopDisplayDrivers.forTarget(target)
                        .features().phoneScreenControl;
    }
}
