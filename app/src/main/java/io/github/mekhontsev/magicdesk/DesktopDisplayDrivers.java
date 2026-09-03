package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Registry and resolution boundary for desktop display drivers. */
final class DesktopDisplayDrivers {
    private static final PlatformDriver PLATFORM = PlatformDrivers.current();
    private static final PlatformFeatures FEATURES = PLATFORM.features();
    private static final DesktopDisplayDriver PHONE = new PhoneDisplayDriver();
    private static final WiredDisplayDriver WIRED =
            new WiredDisplayDriver(PLATFORM.projection());
    private static final DesktopDisplayDriver WIRELESS =
            new WirelessDisplayDriver();
    private static final DesktopDisplayDriver SIMULATED =
            new SimulatedDisplayDriver();

    private DesktopDisplayDrivers() {
    }

    static boolean isSupported(final DesktopDisplayTarget.Kind kind) {
        return FEATURES.supportsDisplay(kind);
    }

    static boolean isExternalDesktopSupported() {
        return FEATURES.supportsExternalDesktop()
                || FEATURES.supportsDisplay(
                        DesktopDisplayTarget.Kind.SIMULATED);
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

    static void activateWired(final android.app.Activity source) {
        activateWired(source, DesktopSessionPolicy.USER);
    }

    static void activateWired(
            final android.app.Activity source,
            final DesktopSessionPolicy policy) {
        WIRED.activate(source, policy);
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

    static boolean hasActiveWorkspace(final int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY
                && DesktopRuntimeBridge.isLocalDesktopActiveOrStarting()) {
            return true;
        }
        return DesktopRuntimeBridge.getDesktopTarget(displayId) != null;
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
