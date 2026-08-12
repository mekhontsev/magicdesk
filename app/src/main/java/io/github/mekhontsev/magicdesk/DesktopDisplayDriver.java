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
