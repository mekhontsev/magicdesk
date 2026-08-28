package io.github.mekhontsev.magicdesk;

import android.app.Activity;

import java.io.IOException;

/** Hosts MagicDesk on a wired display, with optional platform mode switching. */
final class WiredDisplayDriver implements DesktopDisplayDriver {
    private static final DesktopDisplayFeatures FEATURES =
            new DesktopDisplayFeatures(
                    DesktopTaskAreaPolicy.DEFAULT,
                    true,
                    true,
                    true);
    private final PlatformProjectionDriver mProjection;

    WiredDisplayDriver(final PlatformProjectionDriver projection) {
        if (projection == null) {
            throw new IllegalArgumentException("projection driver is required");
        }
        mProjection = projection;
    }

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

    void activate(final Activity source) {
        activate(source, DesktopSessionPolicy.USER);
    }

    void activate(
            final Activity source,
            final DesktopSessionPolicy policy) {
        if (mProjection.ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRED)) {
            ConsoleSessionController.show(
                    android.view.Display.INVALID_DISPLAY,
                    mProjection,
                    policy);
            return;
        }
        final int connectedDisplayId =
                ConsoleDisplayController.findExternalDisplayId();
        if (connectedDisplayId <= 0) {
            CompatibilityDiagnostics.record(
                    "DISPLAY-EXTERNAL-001",
                    "Could not open MagicDesk on the wired display",
                    "no connected wired display was reported");
            return;
        }
        showReady(source, target(connectedDisplayId), policy);
    }

    @Override
    public void showReady(
            final Activity source,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        requireTarget(target);
        if (mProjection.ownsTransportLifecycle(
                PlatformProjectionDriver.Transport.WIRED)) {
            ConsoleSessionController.show(
                    target.displayId, mProjection, policy);
            return;
        }
        DesktopDisplayDriverSupport.showReadySecondary(target, policy);
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
