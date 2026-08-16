package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public final class DesktopRuntimeBridge {
    private static final String TAG = "MagicDesk";
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());
    private static final Object SESSION_LOCK = new Object();

    private static WeakReference<DesktopShellActivity> sShell =
            new WeakReference<>(null);
    private static WeakReference<DesktopShellActivity> sDesktop =
            new WeakReference<>(null);
    private static DesktopSessionSnapshot sSession =
            DesktopSessionSnapshot.empty();

    private DesktopRuntimeBridge() {
    }

    static void registerShell(final DesktopShellActivity activity) {
        synchronized (SESSION_LOCK) {
            sShell = new WeakReference<>(activity);
        }
    }

    static void registerDesktop(final DesktopShellActivity activity) {
        final DesktopShellActivity previous;
        final boolean replacingSameTask;
        final boolean previousWasLocal;
        final int displayId = activity.getCurrentDisplayId();
        synchronized (SESSION_LOCK) {
            previous = sDesktop.get();
            replacingSameTask = previous != null
                    && previous != activity
                    && previous.getTaskId() == activity.getTaskId();
            previousWasLocal = previous != null
                    && previous != activity
                    && previous.getCurrentDisplayId()
                            == Display.DEFAULT_DISPLAY;
            sDesktop = new WeakReference<>(activity);
            sSession = sSession.registerHost(
                    displayId, activity.getTaskId(), replacingSameTask);
        }
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
        final DesktopDisplayTarget target = getSessionSnapshot().target();
        if (displayId == Display.DEFAULT_DISPLAY
                && target != null
                && target.kind == DesktopDisplayTarget.Kind.PHONE
                && ShellAccess.isReady()) {
            LocalDesktopSessionState.markCleanupPending(activity);
        }
        MagicDeskRuntime.refreshDesktopTasks();
        if (previousWasLocal) {
            MagicDeskRuntime.scheduleLocalDesktopCleanup();
        }
    }

    static void unregister(final DesktopShellActivity activity) {
        final boolean changingConfigurations =
                activity.isChangingConfigurations();
        final int displayId = activity.getCurrentDisplayId();
        final boolean desktopRemoved;
        synchronized (SESSION_LOCK) {
            if (sShell.get() == activity) {
                sShell.clear();
            }
            desktopRemoved = sDesktop.get() == activity;
            if (desktopRemoved) {
                sDesktop.clear();
                sSession = sSession.unregisterHost(
                        displayId, changingConfigurations);
            }
        }
        if (!desktopRemoved || changingConfigurations) {
            return;
        }
        MagicDeskRuntime.refreshDesktopTasks();
        if (displayId == Display.DEFAULT_DISPLAY) {
            MagicDeskRuntime.scheduleLocalDesktopCleanup();
        }
    }

    static void closeDesktopSession(final int displayId) {
        final DesktopShellActivity activity;
        synchronized (SESSION_LOCK) {
            activity = usableDesktopLocked(false);
            final DesktopDisplayTarget target = sSession.target();
            if (activity == null
                    || displayId < Display.DEFAULT_DISPLAY
                    || target == null
                    || target.displayId != displayId) {
                return;
            }
            sDesktop.clear();
            if (sShell.get() == activity) {
                sShell.clear();
            }
            sSession = sSession.close();
        }
        final Runnable close = () -> {
            activity.releaseDesktopOverlays();
            if (!activity.isFinishing()) {
                activity.finishAndRemoveTask();
            }
            MagicDeskRuntime.refreshDesktopTasks();
            if (displayId == Display.DEFAULT_DISPLAY) {
                MagicDeskRuntime.scheduleLocalDesktopCleanup();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            close.run();
        } else {
            MAIN_HANDLER.post(close);
        }
    }

    public static int getActiveDesktopDisplayId() {
        return getSessionSnapshot().activeDisplayId();
    }

    static DesktopSessionSnapshot getSessionSnapshot() {
        synchronized (SESSION_LOCK) {
            reconcileSessionHostLocked();
            return sSession;
        }
    }

    static void noteDesktopTarget(final DesktopDisplayTarget target) {
        if (target == null) {
            return;
        }
        synchronized (SESSION_LOCK) {
            sSession = sSession.noteTarget(target);
        }
    }

    static void clearDesktopTarget(final DesktopDisplayTarget target) {
        synchronized (SESSION_LOCK) {
            sSession = sSession.clearTarget(target);
        }
    }

    static DesktopDisplayTarget getDesktopTarget(final int displayId) {
        return getSessionSnapshot().targetForDisplay(displayId);
    }

    static DesktopDisplayTarget getActiveDesktopTarget() {
        return getSessionSnapshot().target();
    }

    static boolean isLocalDesktopActiveOrStarting() {
        return getSessionSnapshot().isLocalActiveOrStarting();
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
        if (viewport == null) {
            return null;
        }
        return activity.isTaskbarAutoHideEnabled()
                ? viewport.contentBounds()
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

    public static void refreshDesktopControls() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return;
        }
        activity.runOnUiThread(activity::updateDesktopControls);
    }

    static boolean refreshDesktopInputFocus(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        activity.runOnUiThread(activity::refreshDesktopInputFocus);
        return true;
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
                        activity != null && activity.hasWindowFocus(),
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
                // A system activity can become focused before the task watcher
                // publishes its next snapshot. Win+D must still expose an
                // immediate route back to the desktop and taskbar.
                activity.setTaskbarVisible(true);
                focusDesktopOnDisplay(displayId);
            }
        });
        return true;
    }

    static boolean recreateShellOnDisplay(final int displayId) {
        final DesktopShellActivity activity;
        synchronized (SESSION_LOCK) {
            activity = sShell.get();
        }
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

    static boolean openSettings() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::openSettings);
        return true;
    }

    static void refreshSettings() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity != null) {
            activity.runOnUiThread(activity::refreshSettings);
        }
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

    static boolean isUsingFallbackDesktopWallpaper(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isUsingFallbackDesktopWallpaper();
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

    static void prepareTaskFocus(
            final int displayId, final int taskId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return;
        }
        activity.setDesktopWindowFocusable(taskId == activity.getTaskId());
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

    private static DesktopShellActivity usableDesktop(
            final boolean requireOverlay) {
        synchronized (SESSION_LOCK) {
            return usableDesktopLocked(requireOverlay);
        }
    }

    private static DesktopShellActivity usableDesktopLocked(
            final boolean requireOverlay) {
        final DesktopShellActivity activity = reconcileSessionHostLocked();
        if (!isUsable(activity) || !activity.isDesktopShell()
                || (requireOverlay && activity.overlayPanels() == null)) {
            return null;
        }
        return activity;
    }

    private static DesktopShellActivity reconcileSessionHostLocked() {
        final DesktopShellActivity activity = sDesktop.get();
        if (!isUsable(activity) || !activity.isDesktopShell()) {
            if (sSession.hasHost()) {
                sSession = sSession.unregisterHost(
                        sSession.activeDisplayId(), true);
            }
            return null;
        }
        sSession = sSession.observeHost(
                activity.getCurrentDisplayId(), activity.getTaskId());
        return activity;
    }

    private static boolean isUsable(final DesktopShellActivity activity) {
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }
}
