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
        if (!isSupported(displayId)) {
            return;
        }
        MagicDeskRuntime.setPhoneTouchpadRequested(true);
        MagicDeskTouchpadActivity.open(
                MagicDeskApplication.applicationContext(), displayId);
    }

    static boolean isVisible() {
        final int displayId = activeDisplayId();
        return isSupported(displayId)
                && MagicDeskTouchpadActivity.isVisible(displayId);
    }

    static boolean shouldRemainVisible(final int displayId) {
        return isSupported(displayId)
                && MagicDeskTouchpadActivity.isRequested(displayId);
    }

    static void restoreIfMissing(
            final DesktopOperations.TouchpadRestoreCallback callback) {
        final int displayId = activeDisplayId();
        final boolean missing = isSupported(displayId)
                && MagicDeskTouchpadActivity.isRequested(displayId)
                && !MagicDeskTouchpadActivity.isVisible(displayId);
        final boolean restored = missing
                && restoreRequestedTask(displayId);
        if (callback != null) {
            callback.onComplete(missing, restored);
        }
    }

    static boolean restoreRequestedTask(final int displayId) {
        if (!isSupported(displayId)
                || !MagicDeskTouchpadActivity.isRequested(displayId)) {
            return false;
        }
        if (MagicDeskTouchpadActivity.bringRequestedTaskToFront(
                MagicDeskApplication.applicationContext(), displayId)) {
            return true;
        }
        return MagicDeskTouchpadActivity.startIfRequested(
                MagicDeskApplication.applicationContext(), displayId);
    }

    static boolean bringRequestedTaskToFront(final int displayId) {
        return isSupported(displayId)
                && MagicDeskTouchpadActivity.bringRequestedTaskToFront(
                        MagicDeskApplication.applicationContext(),
                        displayId);
    }

    static void release(final int displayId) {
        MagicDeskRuntime.setPhoneTouchpadRequested(false);
        MagicDeskTouchpadActivity.release(displayId);
    }

    static boolean isSupported(final int displayId) {
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
        return DesktopRuntimeBridge.getActiveDesktopDisplayId();
    }
}
