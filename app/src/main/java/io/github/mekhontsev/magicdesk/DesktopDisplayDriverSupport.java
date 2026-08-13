package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;

/** Shared session operations used by display drivers. */
final class DesktopDisplayDriverSupport {
    private static final String TAG = "MagicDeskDisplayDriver";

    private DesktopDisplayDriverSupport() {
    }

    static void showPrepared(
            final DesktopDisplayDriver driver,
            final int displayId) {
        showPrepared(driver.target(displayId));
    }

    static void showPrepared(final DesktopDisplayTarget target) {
        final DesktopDisplayDriver driver =
                DesktopDisplayDrivers.forTarget(target);
        try {
            final DesktopSessionController.ShowResult result =
                    DesktopSessionController.show(target);
            if (result.ready && result.created
                    && driver.features().phoneTouchpad) {
                PhoneTouchpadController.open(target.displayId);
            }
        } catch (IOException error) {
            Log.w(TAG, "Desktop launch failed", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-LAUNCH-002",
                    "Could not open MagicDesk on the selected display",
                    "kind=" + driver.kind()
                            + " display=" + target.displayId
                            + " error=" + error.getMessage(),
                    error);
        }
    }

    static void complete(
            final DesktopDisplayDriver.CompletionCallback callback,
            final boolean success) {
        if (callback != null) {
            callback.onComplete(success);
        }
    }
}
