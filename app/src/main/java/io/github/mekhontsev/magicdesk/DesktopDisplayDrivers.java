package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Registry and resolution boundary for desktop display drivers. */
final class DesktopDisplayDrivers {
    private static final DesktopDisplayDriver PHONE = new PhoneDisplayDriver();
    private static final DesktopDisplayDriver WIRED = new WiredDisplayDriver();
    private static final DesktopDisplayDriver WIRELESS =
            new WirelessDisplayDriver();
    private static final DesktopDisplayDriver SIMULATED =
            new SimulatedDisplayDriver();

    private DesktopDisplayDrivers() {
    }

    static boolean isSupported(final DesktopDisplayTarget.Kind kind) {
        return PlatformDrivers.current().features().supportsDisplay(kind);
    }

    static boolean isExternalDesktopSupported() {
        return PlatformDrivers.current().features().supportsExternalDesktop();
    }

    static DesktopDisplayDriver forKind(
            final DesktopDisplayTarget.Kind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("display kind is required");
        }
        switch (kind) {
            case PHONE:
                return PHONE;
            case WIRED:
                return WIRED;
            case WIRELESS:
                return WIRELESS;
            case SIMULATED:
                return SIMULATED;
            default:
                throw new IllegalArgumentException(
                        "unsupported display kind " + kind);
        }
    }

    static DesktopDisplayDriver forTarget(
            final DesktopDisplayTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("display target is required");
        }
        return forKind(target.kind);
    }

    static DesktopDisplayDriver forActiveDisplay(final int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return PHONE;
        }
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(displayId);
        if (target == null) {
            throw new IllegalStateException(
                    "desktop target is unavailable for display " + displayId);
        }
        return forTarget(target);
    }

    static int captureDisplayId(final int desktopDisplayId) {
        final DesktopDisplayTarget target = activeTarget(desktopDisplayId);
        return forTarget(target).captureDisplayId(target);
    }

    static DisplayCaptureSource captureSource(final int desktopDisplayId) {
        final DesktopDisplayTarget target = activeTarget(desktopDisplayId);
        return forTarget(target).captureSource(target);
    }

    private static DesktopDisplayTarget activeTarget(
            final int desktopDisplayId) {
        final DesktopDisplayTarget target = desktopDisplayId
                == Display.DEFAULT_DISPLAY
                ? DesktopDisplayTarget.phone()
                : DesktopRuntimeBridge.getDesktopTarget(desktopDisplayId);
        if (target == null) {
            throw new IllegalStateException(
                    "desktop target is unavailable for display "
                            + desktopDisplayId);
        }
        return target;
    }
}
