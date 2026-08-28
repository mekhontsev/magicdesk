package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Defines behavior that must stay consistent across desktop displays. */
final class DesktopScreenPolicy {
    private DesktopScreenPolicy() {
    }

    static boolean isExternalDesktopSession(
            final int desktopDisplayId,
            final int activeDesktopDisplayId) {
        return isExternalDesktop(desktopDisplayId)
                && activeDesktopDisplayId == desktopDisplayId;
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
