package io.github.mekhontsev.magicdesk;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;

final class DesktopRuntimeBridge {
    private static final String TAG = "MagicDesk";
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private static WeakReference<MainActivity> sShell =
            new WeakReference<>(null);
    private static WeakReference<MainActivity> sDesktop =
            new WeakReference<>(null);

    private DesktopRuntimeBridge() {
    }

    static void registerShell(final MainActivity activity) {
        sShell = new WeakReference<>(activity);
    }

    static void registerDesktop(final MainActivity activity) {
        final MainActivity previous = sDesktop.get();
        if (previous != null && previous != activity) {
            // Nubia may migrate the phone task before the dedicated Console HOME starts.
            Log.i(TAG, "replacing desktop shell task=" + previous.getTaskId()
                    + " with task=" + activity.getTaskId());
            previous.releaseDesktopOverlays();
            if (previous.getTaskId() != activity.getTaskId()
                    && !previous.isFinishing()) {
                previous.finishAndRemoveTask();
            }
        }
        sDesktop = new WeakReference<>(activity);
    }

    static void unregister(final MainActivity activity) {
        if (sShell.get() == activity) {
            sShell.clear();
        }
        if (sDesktop.get() == activity) {
            sDesktop.clear();
        }
    }

    static boolean showStart() {
        final MainActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::showStartFromRuntime);
        return true;
    }

    static boolean restoreLastVisibleWindows() {
        final MainActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::restoreLastVisibleWindows);
        return true;
    }

    static boolean recreateShellOnDisplay(final int displayId) {
        final MainActivity activity = sShell.get();
        if (!isUsable(activity) || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        MAIN_HANDLER.post(() -> {
            if (!activity.isActivityUnavailable()) {
                activity.recreate();
            }
        });
        return true;
    }

    static boolean advanceAltTab(final boolean reverse) {
        final MainActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(() -> activity.advanceAltTab(reverse));
        return true;
    }

    static boolean finishAltTab() {
        final MainActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::finishAltTab);
        return true;
    }

    static boolean cancelAltTab() {
        final MainActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::cancelAltTabFromRuntime);
        return true;
    }

    static boolean toggleShortcutHelp() {
        final MainActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::toggleShortcutHelp);
        return true;
    }

    static boolean toggleNotificationCenter() {
        final MainActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity.notifications()::toggle);
        return true;
    }

    static boolean isDesktopReadyOnDisplay(final int displayId) {
        final MainActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && !activity.isInMultiWindowMode();
    }

    static void syncTaskbarWithSnapshot(
            final int displayId,
            final TaskRepository.Snapshot snapshot) {
        final MainActivity activity = usableDesktop(false);
        if (activity == null || snapshot == null || !snapshot.rootAvailable
                || activity.getCurrentDisplayId() != displayId) {
            return;
        }
        activity.runOnUiThread(() -> activity.syncTaskbarWithSnapshot(snapshot));
    }

    private static MainActivity usableDesktop(final boolean requireOverlay) {
        final MainActivity activity = sDesktop.get();
        if (!isUsable(activity) || !activity.isDesktopShell()
                || (requireOverlay && activity.overlayPanels() == null)) {
            return null;
        }
        return activity;
    }

    private static boolean isUsable(final MainActivity activity) {
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }
}
