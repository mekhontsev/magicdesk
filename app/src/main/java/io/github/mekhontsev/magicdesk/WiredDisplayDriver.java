package io.github.mekhontsev.magicdesk;

import android.app.Activity;

import java.io.IOException;

/** Hosts MagicDesk on a wired display, with optional platform mode switching. */
final class WiredDisplayDriver implements DesktopDisplayDriver {
    private static final DesktopDisplayFeatures FEATURES =
            new DesktopDisplayFeatures(false, true, true, true);

    @Override
    public DesktopDisplayTarget.Kind kind() {
        return DesktopDisplayTarget.Kind.WIRED;
    }

    @Override
    public DesktopDisplayFeatures features() {
        return FEATURES;
    }

    @Override
    public DesktopDisplayTarget target(final int displayId) {
        return DesktopDisplayTarget.wired(displayId);
    }

    @Override
    public int captureDisplayId(final DesktopDisplayTarget target) {
        requireTarget(target);
        if (!isBackedBySeparateOutput(target)) {
            return target.displayId;
        }
        // Nubia hosts tasks on a virtual display backed by this physical output.
        return target.profileDisplayId;
    }

    @Override
    public DisplayCaptureSource captureSource(
            final DesktopDisplayTarget target) {
        if (!isBackedBySeparateOutput(target)) {
            requireTarget(target);
            return DisplayCaptureSource.logical(target.displayId);
        }
        final int logicalDisplayId = captureDisplayId(target);
        try {
            return DisplayCaptureSource.physical(
                    logicalDisplayId,
                    ConsoleDisplayController.getPhysicalDisplayId(
                            logicalDisplayId));
        } catch (IOException ignored) {
            // Some Android builds expose only a capturable logical display.
            return DisplayCaptureSource.logical(logicalDisplayId);
        }
    }

    @Override
    public void show(final Activity source, final int displayId) {
        if (DesktopDisplayDriverSupport.ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRED)) {
            ConsoleSessionController.show(displayId);
            return;
        }
        final int connectedDisplayId = displayId > 0
                ? displayId : ConsoleDisplayController.findExternalDisplayId();
        if (connectedDisplayId <= 0) {
            CompatibilityDiagnostics.record(
                    "DISPLAY-EXTERNAL-001",
                    "Could not open MagicDesk on the wired display",
                    "no connected wired display was reported");
            return;
        }
        DesktopDisplayDriverSupport.showConnectedExternal(
                this, connectedDisplayId);
    }

    @Override
    public void close(
            final DesktopDisplayTarget target,
            final boolean restorePhonePanel,
            final CompletionCallback callback) {
        requireTarget(target);
        DesktopDisplayDriverSupport.closeExternal(
                target,
                PlatformProjectionDriver.Transport.WIRED,
                restorePhonePanel,
                callback);
    }

    private static boolean isBackedBySeparateOutput(
            final DesktopDisplayTarget target) {
        return target != null
                && target.hasProfile()
                && target.profileDisplayId != target.displayId;
    }

    @Override
    public boolean isSessionDisplayRemoval(
            final DesktopDisplayTarget target,
            final int removedDisplayId,
            final boolean activeDesktopRemoved) {
        requireTarget(target);
        return target.displayId == removedDisplayId;
    }

    private static void requireTarget(final DesktopDisplayTarget target) {
        if (target == null || target.kind != DesktopDisplayTarget.Kind.WIRED) {
            throw new IllegalArgumentException("wired target is required");
        }
    }
}
