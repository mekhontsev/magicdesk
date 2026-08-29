package io.github.mekhontsev.magicdesk;

import android.app.Activity;

import java.io.IOException;

/** Hosts MagicDesk directly on a connected physical wired display. */
final class WiredDisplayDriver implements DesktopDisplayDriver {
    private static final String TAG = "MagicDeskWiredDisplay";
    private static final DesktopDisplayFeatures FEATURES =
            new DesktopDisplayFeatures(
                    DesktopTaskAreaPolicy.INDEPENDENT,
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
        final android.content.Context context =
                MagicDeskApplication.applicationContext();
        final DesktopDisplayTarget profiledTarget =
                DisplayProfileController.prepareTarget(context, target);
        final DisplayProfileStore.Profile profile =
                DisplayProfileController.loadPreparedProfile(
                        context, profiledTarget);
        PlatformProjectionDriver.PreparedMode preparedMode = null;
        try {
            DesktopDisplayTarget readyTarget = profiledTarget;
            if (mProjection.supportsOutputConfiguration()) {
                preparedMode = mProjection.prepareExternalDisplay(
                        context,
                        profiledTarget.profileDisplayId,
                        profile);
                preparedMode.applyDeferredMode();
                final int currentDisplayId =
                        ExternalDisplayController.findExternalDisplayId();
                if (currentDisplayId <= android.view.Display.DEFAULT_DISPLAY) {
                    throw new IOException(
                            "wired display disappeared during output setup");
                }
                readyTarget = DesktopDisplayTarget.wired(currentDisplayId)
                        .withActivationSource(target.activationSource);
                if (profile != null) {
                    readyTarget = readyTarget.withProfile(
                            currentDisplayId, profile.key);
                }
            }
            ExternalDisplayController.ensureLandscape(readyTarget.displayId);
            DesktopDisplayDriverSupport.showReadySecondary(
                    readyTarget, policy);
        } catch (IOException | RuntimeException error) {
            android.util.Log.w(TAG, "Wired display preparation failed", error);
            CompatibilityDiagnostics.record(
                    "DISPLAY-MODE-001",
                    "Could not prepare the wired display",
                    error.getMessage(),
                    error);
        } finally {
            if (preparedMode != null) {
                preparedMode.close();
            }
        }
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
