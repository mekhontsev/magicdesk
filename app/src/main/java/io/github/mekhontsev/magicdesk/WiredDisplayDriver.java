package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Hosts MagicDesk through RedMagic's wired Console Mode transport. */
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
        if (!target.hasProfile()) {
            throw new IllegalStateException("wired capture output is unavailable");
        }
        // Nubia hosts tasks on a virtual display backed by this physical output.
        return target.profileDisplayId;
    }

    @Override
    public void show(final Activity source, final int displayId) {
        ConsoleSessionController.show(displayId);
    }

    @Override
    public void close(
            final DesktopDisplayTarget target,
            final boolean restorePhonePanel,
            final CompletionCallback callback) {
        requireTarget(target);
        if (restorePhonePanel) {
            ConsoleModeSwitcher.switchToMirrorWithControlPanel(
                    success -> DesktopDisplayDriverSupport.complete(
                            callback, success));
        } else {
            ConsoleModeSwitcher.switchToMirror(
                    success -> DesktopDisplayDriverSupport.complete(
                            callback, success));
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
