package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Owns transport-specific lifecycle and policy for one display environment. */
interface DesktopDisplayDriver {
    DesktopDisplayTarget.Kind kind();

    DesktopDisplayFeatures features();

    DesktopDisplayTarget target(int displayId);

    /** Logical display whose compositor output represents this desktop. */
    default int captureDisplayId(final DesktopDisplayTarget target) {
        if (target == null || target.kind != kind()) {
            throw new IllegalArgumentException("matching display target is required");
        }
        return target.displayId;
    }

    default DisplayCaptureSource captureSource(
            final DesktopDisplayTarget target) {
        return DisplayCaptureSource.logical(captureDisplayId(target));
    }

    /** Opens a normal user desktop on a ready logical task-host display. */
    default void showReady(
            final Activity source,
            final DesktopDisplayTarget target) {
        showReady(source, target, DesktopSessionPolicy.USER);
    }

    /** Opens the desktop with an explicit workspace lifecycle policy. */
    void showReady(
            Activity source,
            DesktopDisplayTarget target,
            DesktopSessionPolicy policy);

    boolean isSessionDisplayRemoval(
            DesktopDisplayTarget target,
            int removedDisplayId,
            boolean activeDesktopRemoved);
}
