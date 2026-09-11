package io.github.mekhontsev.magicdesk;

import android.app.Activity;

import java.io.IOException;

/** Hosts MagicDesk directly on a connected physical wired display. */
final class WiredDisplayDriver implements DesktopDisplayDriver {
    private static final String TAG = "MagicDeskWiredDisplay";
    private static final DesktopDisplayFeatures FEATURES =
            new DesktopDisplayFeatures(
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
    public DesktopDisplayOutput.Kind kind() {
        return DesktopDisplayOutput.Kind.WIRED;
    }

    @Override
    public DesktopDisplayFeatures features() {
        return FEATURES;
    }

    @Override
    public DesktopDisplayTarget target(final int displayId) {
        return DesktopDisplayTarget.wired(displayId);
    }

    void activate(final Activity source) {
        activate(source, DesktopSessionPolicy.USER);
    }

    void activate(
            final Activity source,
            final DesktopSessionPolicy policy) {
        final int connectedDisplayId =
                ExternalDisplayController.findExternalDisplayId();
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
        target.requireSupportedBinding();
        final android.content.Context context =
                MagicDeskApplication.applicationContext();
        final DesktopDisplayTarget profiledTarget =
                DisplayProfileController.prepareTarget(context, target);
        final DisplayProfileStore.Profile profile =
                DisplayProfileController.loadPreparedProfile(
                        context, profiledTarget);
        try {
            DesktopDisplayTarget readyTarget = profiledTarget;
            final String uniqueId = DesktopDisplayCatalog.require(target.output.displayId, null).uniqueId;
            if (mProjection.supportsOutputConfiguration()) {
                mProjection.prepareExternalDisplay(
                        context,
                        profiledTarget.output.displayId,
                        profile);
                int currentDisplayId = android.view.Display.INVALID_DISPLAY;
                for (final DesktopDisplayInfo display : DesktopDisplayCatalog.read()) {
                    if (uniqueId.equals(display.uniqueId)) { currentDisplayId = display.id; break; }
                }
                if (currentDisplayId <= android.view.Display.DEFAULT_DISPLAY) {
                    throw new IOException(
                            "wired display disappeared during output setup");
                }
                readyTarget = DesktopDisplayTarget.wired(currentDisplayId)
                        .withActivationSource(target.output.activationSource);
                if (profile != null) {
                    readyTarget = readyTarget.withProfile(
                            profile.key);
                }
            }
            ExternalDisplayController.ensureLandscape(readyTarget.workspaceDisplayId);
            DesktopDisplayDriverSupport.showReadySecondary(
                    readyTarget, policy);
        } catch (IOException | RuntimeException error) {
            android.util.Log.w(TAG, "Wired display preparation failed", error);
            CompatibilityDiagnostics.record(
                    "DISPLAY-MODE-001",
                    "Could not prepare the wired display",
                    error.getMessage(),
                    error);
        }
    }

    @Override
    public boolean isSessionDisplayRemoval(
            final DesktopDisplayTarget target,
            final int removedDisplayId,
            final boolean activeDesktopRemoved) {
        requireTarget(target);
        return target.workspaceDisplayId == removedDisplayId;
    }

    private static void requireTarget(final DesktopDisplayTarget target) {
        if (target == null || target.output.kind != DesktopDisplayOutput.Kind.WIRED) {
            throw new IllegalArgumentException("wired target is required");
        }
    }
}
