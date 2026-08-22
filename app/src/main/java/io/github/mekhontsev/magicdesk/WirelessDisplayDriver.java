package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Hosts MagicDesk on an already connected Miracast display. */
final class WirelessDisplayDriver implements DesktopDisplayDriver {
    private static final DesktopDisplayFeatures FEATURES =
            new DesktopDisplayFeatures(
                    DesktopTaskAreaPolicy.DEFAULT,
                    true,
                    true,
                    true);

    @Override
    public DesktopDisplayTarget.Kind kind() {
        return DesktopDisplayTarget.Kind.WIRELESS;
    }

    @Override
    public DesktopDisplayFeatures features() {
        return FEATURES;
    }

    @Override
    public DesktopDisplayTarget target(final int displayId) {
        return DesktopDisplayTarget.wireless(displayId);
    }

    @Override
    public void showReady(
            final Activity source,
            final DesktopDisplayTarget target) {
        requireTarget(target);
        DesktopDisplayDriverSupport.showReadySecondary(target);
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
        if (target == null || target.kind != DesktopDisplayTarget.Kind.WIRELESS) {
            throw new IllegalArgumentException("wireless target is required");
        }
    }
}
