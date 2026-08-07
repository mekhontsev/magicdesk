package io.github.mekhontsev.magicdesk;

import android.util.Log;
import android.view.Display;

/** Selects and maintains the phone-side touchpad for the active desktop transport. */
final class PhoneTouchpadController {
    private static final String TAG = "MagicDeskTouchpad";

    private PhoneTouchpadController() {
    }

    static void open() {
        if (ConsoleModeSwitcher.getActiveConsoleDisplayId()
                > Display.DEFAULT_DISPLAY) {
            NubiaTouchpadController.open();
            return;
        }
        final int displayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        if (displayId > Display.DEFAULT_DISPLAY) {
            MagicDeskTouchpadActivity.open(
                    MagicDeskApplication.applicationContext(), displayId);
            return;
        }
        Log.w(TAG, "cannot open touchpad: no external desktop is active");
    }

    static boolean isVisible() {
        if (ConsoleModeSwitcher.getActiveConsoleDisplayId()
                > Display.DEFAULT_DISPLAY) {
            return NubiaTouchpadController.isVisible();
        }
        final int displayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        return displayId > Display.DEFAULT_DISPLAY
                && MagicDeskTouchpadActivity.isVisible(displayId);
    }

    static boolean shouldRemainVisible(final int displayId) {
        return displayId > Display.DEFAULT_DISPLAY
                && MagicDeskTouchpadActivity.isRequested(displayId);
    }

    static void restoreIfMissing(
            final ConsoleModeSwitcher.TouchpadRestoreCallback callback) {
        if (ConsoleModeSwitcher.getActiveConsoleDisplayId()
                > Display.DEFAULT_DISPLAY) {
            NubiaTouchpadController.restoreIfMissing(callback);
            return;
        }
        final int displayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final boolean missing = displayId > Display.DEFAULT_DISPLAY
                && MagicDeskTouchpadActivity.isRequested(displayId)
                && !MagicDeskTouchpadActivity.isVisible(displayId);
        final boolean restored = missing
                && MagicDeskTouchpadActivity.restoreIfRequested(
                        MagicDeskApplication.applicationContext(), displayId);
        if (callback != null) {
            callback.onComplete(missing, restored);
        }
    }

    static void release(final int displayId) {
        MagicDeskTouchpadActivity.release(displayId);
    }
}
