package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Models an external desktop on an owned Android overlay display. */
final class SimulatedDisplayDriver implements DesktopDisplayDriver {
    private static final DesktopDisplayFeatures FEATURES =
            new DesktopDisplayFeatures(
                    DesktopTaskAreaPolicy.INDEPENDENT,
                    false,
                    true);

    @Override
    public DesktopDisplayTarget.Kind kind() {
        return DesktopDisplayTarget.Kind.SIMULATED;
    }

    @Override
    public DesktopDisplayFeatures features() {
        return FEATURES;
    }

    @Override
    public DesktopDisplayTarget target(final int displayId) {
        return DesktopDisplayTarget.simulated(displayId);
    }

    @Override
    public void showReady(
            final Activity source,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        requireTarget(target);
        DesktopDisplayDriverSupport.showReadySecondary(target, policy);
    }

    @Override
    public boolean isSessionDisplayRemoval(
            final DesktopDisplayTarget target,
            final int removedDisplayId,
            final boolean activeDesktopRemoved) {
        requireTarget(target);
        return target.displayId == removedDisplayId
                && activeDesktopRemoved;
    }

    private static void requireTarget(final DesktopDisplayTarget target) {
        if (target == null || target.kind != DesktopDisplayTarget.Kind.SIMULATED) {
            throw new IllegalArgumentException("simulated target is required");
        }
    }
}
