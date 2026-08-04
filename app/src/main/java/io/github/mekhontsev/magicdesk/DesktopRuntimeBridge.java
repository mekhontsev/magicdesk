package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.lang.ref.WeakReference;

final class DesktopRuntimeBridge {
    private static final String TAG = "MagicDesk";
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private static WeakReference<DesktopShellActivity> sShell =
            new WeakReference<>(null);
    private static WeakReference<DesktopShellActivity> sDesktop =
            new WeakReference<>(null);
    // WMS may move the task to display 0 before reporting the external display removal.
    private static int sDesktopSessionDisplayId = Display.INVALID_DISPLAY;

    private DesktopRuntimeBridge() {
    }

    static void registerShell(final DesktopShellActivity activity) {
        sShell = new WeakReference<>(activity);
    }

    static void registerDesktop(final DesktopShellActivity activity) {
        final DesktopShellActivity previous = sDesktop.get();
        final boolean replacingSameTask = previous != null
                && previous != activity
                && previous.getTaskId() == activity.getTaskId();
        final boolean previousWasLocal =
                previous != null
                        && previous != activity
                        && previous.getCurrentDisplayId()
                                == Display.DEFAULT_DISPLAY;
        if (previous != null && previous != activity) {
            // Nubia may move the phone task before the dedicated Console HOME starts.
            Log.i(TAG, "replacing desktop shell task=" + previous.getTaskId()
                    + " with task=" + activity.getTaskId());
            previous.releaseDesktopOverlays();
            if (previous.getTaskId() != activity.getTaskId()
                    && !previous.isFinishing()) {
                previous.finishAndRemoveTask();
            }
        }
        sDesktop = new WeakReference<>(activity);
        final int displayId = activity.getCurrentDisplayId();
        if (!replacingSameTask || displayId != Display.DEFAULT_DISPLAY) {
            sDesktopSessionDisplayId = displayId;
        }
        if (displayId == Display.DEFAULT_DISPLAY
                && sDesktopSessionDisplayId == Display.DEFAULT_DISPLAY
                && ShellAccess.isReady()) {
            LocalDesktopSessionState.markCleanupPending(activity);
        }
        MagicDeskRuntimeService.refreshDesktopTasksIfRunning();
        if (previousWasLocal) {
            MagicDeskRuntimeService.scheduleLocalDesktopCleanupIfRunning();
        }
    }

    static void unregister(final DesktopShellActivity activity) {
        if (sShell.get() == activity) {
            sShell.clear();
        }
        if (sDesktop.get() == activity) {
            final int displayId = activity.getCurrentDisplayId();
            sDesktop.clear();
            sDesktopSessionDisplayId = Display.INVALID_DISPLAY;
            MagicDeskRuntimeService.refreshDesktopTasksIfRunning();
            if (displayId == Display.DEFAULT_DISPLAY) {
                MagicDeskRuntimeService.scheduleLocalDesktopCleanupIfRunning();
            }
        }
    }

    static void closeExternalDesktopSession(final int consoleDisplayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || consoleDisplayId <= Display.DEFAULT_DISPLAY
                || sDesktopSessionDisplayId != consoleDisplayId) {
            return;
        }
        sDesktop.clear();
        if (sShell.get() == activity) {
            sShell.clear();
        }
        sDesktopSessionDisplayId = Display.INVALID_DISPLAY;
        final Runnable close = () -> {
            FreeformLaunchAnchorActivity.release();
            activity.releaseDesktopOverlays();
            if (!activity.isFinishing()) {
                activity.finishAndRemoveTask();
            }
            MagicDeskRuntimeService.refreshDesktopTasksIfRunning();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            close.run();
        } else {
            MAIN_HANDLER.post(close);
        }
    }

    static int getActiveDesktopDisplayId() {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity == null ? -1 : activity.getCurrentDisplayId();
    }

    static DesktopViewport getDesktopViewport(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || activity.getCurrentDisplayId() != displayId) {
            return null;
        }
        return activity.getDesktopViewport();
    }

    static Rect getDesktopWorkAreaBounds(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || activity.getCurrentDisplayId() != displayId) {
            return null;
        }
        final DesktopViewport viewport = activity.getDesktopViewport();
        return viewport == null ? null
                : viewport.workAreaBounds(activity.getTaskbarHeight());
    }

    static boolean showStart() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::showStartFromRuntime);
        return true;
    }

    static void refreshConsoleControls() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(activity::updateConsoleControls);
    }

    static boolean restoreLastVisibleWindows() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::restoreLastVisibleWindows);
        return true;
    }

    static boolean recreateShellOnDisplay(final int displayId) {
        final DesktopShellActivity activity = sShell.get();
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
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(() -> activity.advanceAltTab(reverse));
        return true;
    }

    static boolean finishAltTab() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::finishAltTab);
        return true;
    }

    static boolean cancelAltTab() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::cancelAltTabFromRuntime);
        return true;
    }

    static boolean toggleShortcutHelp() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::toggleShortcutHelp);
        return true;
    }

    static boolean toggleNotificationCenter() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity.notifications()::toggle);
        return true;
    }

    static boolean isDesktopReadyOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isDesktopHostReady();
    }

    static boolean focusDesktopOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        final ActivityManager manager =
                activity.getSystemService(ActivityManager.class);
        if (manager == null) {
            return false;
        }
        for (final ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                final ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.taskId == activity.getTaskId()) {
                    task.moveToFront();
                    return true;
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not focus the desktop task", error);
            }
        }
        return false;
    }

    static void syncTaskbarWithSnapshot(
            final int displayId,
            final TaskRepository.Snapshot snapshot) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || snapshot == null || !snapshot.available
                || activity.getCurrentDisplayId() != displayId) {
            return;
        }
        activity.runOnUiThread(() -> activity.syncTaskbarWithSnapshot(snapshot));
    }

    private static DesktopShellActivity usableDesktop(final boolean requireOverlay) {
        final DesktopShellActivity activity = sDesktop.get();
        if (!isUsable(activity) || !activity.isDesktopShell()
                || (requireOverlay && activity.overlayPanels() == null)) {
            return null;
        }
        return activity;
    }

    private static boolean isUsable(final DesktopShellActivity activity) {
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }
}
