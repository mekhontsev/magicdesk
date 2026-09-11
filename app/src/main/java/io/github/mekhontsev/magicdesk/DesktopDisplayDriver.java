package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Prepares one output transport for a directly hosted desktop. */
interface DesktopDisplayDriver {
    DesktopDisplayOutput.Kind kind();

    DesktopDisplayFeatures features();

    DesktopDisplayTarget target(int displayId);

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
