package io.github.mekhontsev.magicdesk;

import android.util.Log;
import android.view.Display;

/** Opens and maintains MagicDesk's phone-side touchpad. */
final class PhoneTouchpadController {
    private static final String TAG = "MagicDeskTouchpad";

    private PhoneTouchpadController() {
    }

    static void open() {
        final int displayId = activeDisplayId();
        if (displayId > Display.DEFAULT_DISPLAY) {
            open(displayId);
            return;
        }
        Log.w(TAG, "cannot open touchpad: no external desktop is active");
    }

    static void open(final int displayId) {
        if (!supports(displayId)) {
            return;
        }
        MagicDeskTouchpadActivity.open(
                MagicDeskApplication.applicationContext(), displayId);
    }

    static boolean isVisible() {
        final int displayId = activeDisplayId();
        return supports(displayId)
                && MagicDeskTouchpadActivity.isVisible(displayId);
    }

    static boolean shouldRemainVisible(final int displayId) {
        return supports(displayId)
                && MagicDeskTouchpadActivity.isRequested(displayId);
    }

    static void restoreIfMissing(
            final ConsoleModeSwitcher.TouchpadRestoreCallback callback) {
        final int displayId = activeDisplayId();
        final boolean missing = supports(displayId)
                && MagicDeskTouchpadActivity.isRequested(displayId)
                && !MagicDeskTouchpadActivity.isVisible(displayId);
        final boolean restored = missing
                && MagicDeskTouchpadActivity.restoreIfRequested(
                        MagicDeskApplication.applicationContext(), displayId);
        if (callback != null) {
            callback.onComplete(missing, restored);
        }
    }

    static boolean restoreObservedMissing(final int displayId) {
        return supports(displayId)
                && MagicDeskTouchpadActivity.restoreObservedMissing(
                        MagicDeskApplication.applicationContext(),
                        displayId);
    }

    static boolean bringRequestedTaskToFront(final int displayId) {
        return supports(displayId)
                && MagicDeskTouchpadActivity.bringRequestedTaskToFront(
                        MagicDeskApplication.applicationContext(),
                        displayId);
    }

    static void release(final int displayId) {
        MagicDeskTouchpadActivity.release(displayId);
    }

    private static boolean supports(final int displayId) {
        if (displayId <= Display.DEFAULT_DISPLAY) {
            return false;
        }
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(displayId);
        return target != null
                && PlatformDrivers.current().pointer().isAvailable()
                && DesktopDisplayDrivers.forTarget(target)
                        .features().phoneTouchpad;
    }

    private static int activeDisplayId() {
        final int consoleDisplayId =
                ConsoleModeSwitcher.getActiveConsoleDisplayId();
        if (consoleDisplayId > Display.DEFAULT_DISPLAY) {
            return consoleDisplayId;
        }
        final int desktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        return desktopDisplayId;
    }
}
