package io.github.mekhontsev.magicdesk;

import android.app.Activity;

/** Owns transport-specific lifecycle and policy for one display environment. */
interface DesktopDisplayDriver {
    interface CompletionCallback {
        void onComplete(boolean success);
    }

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

    void show(Activity source, int displayId);

    void close(
            DesktopDisplayTarget target,
            boolean restorePhonePanel,
            CompletionCallback callback);

    boolean isSessionDisplayRemoval(
            DesktopDisplayTarget target,
            int removedDisplayId,
            boolean activeDesktopRemoved);
}
