package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

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
    private static volatile int sDesktopSessionDisplayId =
            Display.INVALID_DISPLAY;
    private static volatile DesktopDisplayTarget.Kind sDesktopSessionKind;

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
            if (sDesktopSessionDisplayId != displayId) {
                sDesktopSessionKind = null;
            }
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
            if (activity.isChangingConfigurations()) {
                sDesktop.clear();
                return;
            }
            final int displayId = activity.getCurrentDisplayId();
            sDesktop.clear();
            if (displayId == sDesktopSessionDisplayId
                    || sDesktopSessionDisplayId == Display.DEFAULT_DISPLAY) {
                sDesktopSessionDisplayId = Display.INVALID_DISPLAY;
                sDesktopSessionKind = null;
            }
            MagicDeskRuntimeService.refreshDesktopTasksIfRunning();
            if (displayId == Display.DEFAULT_DISPLAY) {
                MagicDeskRuntimeService.scheduleLocalDesktopCleanupIfRunning();
            }
        }
    }

    static void closeExternalDesktopSession(final int externalDisplayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || externalDisplayId <= Display.DEFAULT_DISPLAY
                || sDesktopSessionDisplayId != externalDisplayId) {
            return;
        }
        sDesktop.clear();
        if (sShell.get() == activity) {
            sShell.clear();
        }
        sDesktopSessionDisplayId = Display.INVALID_DISPLAY;
        sDesktopSessionKind = null;
        final Runnable close = () -> {
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

    static void noteDesktopTarget(final DesktopDisplayTarget target) {
        if (target == null) {
            return;
        }
        sDesktopSessionDisplayId = target.displayId;
        sDesktopSessionKind = target.kind;
    }

    static void clearDesktopTarget(final DesktopDisplayTarget target) {
        if (target == null
                || sDesktopSessionDisplayId != target.displayId
                || sDesktopSessionKind != target.kind) {
            return;
        }
        sDesktopSessionDisplayId = Display.INVALID_DISPLAY;
        sDesktopSessionKind = null;
    }

    static DesktopDisplayTarget.Kind getDesktopTargetKind(final int displayId) {
        return sDesktopSessionDisplayId == displayId
                ? sDesktopSessionKind : null;
    }

    static boolean isSimulatedDesktopDisplay(final int displayId) {
        return getDesktopTargetKind(displayId)
                == DesktopDisplayTarget.Kind.SIMULATED;
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

    static boolean dispatchOverlayTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId
                || Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }
        final OverlayPanelController overlays = activity.overlayPanels();
        return overlays != null && overlays.dispatchTextInput(
                action, text, arg1, arg2, arg3);
    }

    static boolean hasOverlayTextInput(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId
                || Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }
        final OverlayPanelController overlays = activity.overlayPanels();
        return overlays != null && overlays.hasTextInputTarget();
    }

    static void showTransientStatus(
            final String message,
            final boolean longDuration) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(() -> {
            activity.setStatus(message);
            Toast.makeText(
                    activity,
                    message,
                    longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT)
                    .show();
        });
    }

    static void refreshDesktopControls() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(activity::updateDesktopControls);
    }

    static boolean restoreLastVisibleWindows() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::restoreLastVisibleWindows);
        return true;
    }

    static boolean toggleDesktopWorkspace() {
        final DesktopShellActivity activity = usableDesktop(false);
        final int displayId = activity == null
                ? Display.INVALID_DISPLAY : activity.getCurrentDisplayId();
        final DesktopScreenPolicy.WorkspaceAction action =
                DesktopScreenPolicy.workspaceAction(
                        displayId,
                        displayId < 0 ? null
                                : DesktopTaskController
                                        .hasVisibleAppTaskSnapshot(displayId));
        if (activity == null
                || action == DesktopScreenPolicy.WorkspaceAction
                        .START_EXTERNAL_DESKTOP) {
            return false;
        }
        activity.runOnUiThread(() -> {
            if (!isUsable(activity)) {
                return;
            }
            activity.hideAllPanels();
            if (action == DesktopScreenPolicy.WorkspaceAction.RESTORE_WINDOWS) {
                activity.restoreLastVisibleWindows();
            } else {
                focusDesktopOnDisplay(displayId);
            }
        });
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

    static boolean toggleSystemPanel() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::toggleSystemPanel);
        return true;
    }

    static boolean isDesktopReadyOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isDesktopHostReady();
    }

    static boolean isDesktopWallpaperRendered(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isDesktopWallpaperRendered();
    }

    static int getDesktopHostIdentity(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                        && activity.getCurrentDisplayId() == displayId
                ? System.identityHashCode(activity) : 0;
    }

    static boolean isDesktopWindowFocused(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.hasWindowFocus();
    }

    static boolean focusDesktopOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        final int taskId = activity.getTaskId();
        DesktopTaskController.focusDesktopTask(
                displayId,
                taskId,
                result -> {
                    if (!result.success) {
                        Log.w(TAG, "Could not focus desktop task=" + taskId
                                + " display=" + displayId
                                + " result=" + result.message);
                    }
                });
        return true;
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
