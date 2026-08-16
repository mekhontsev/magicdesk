package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/** Process-local gateway to the live desktop host Activity. */
final class DesktopUiGateway {
    private static final String TAG = "MagicDesk";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Object mHostLock = new Object();
    private final DesktopSessionRegistry mSession;

    private WeakReference<DesktopShellActivity> mShell =
            new WeakReference<>(null);
    private WeakReference<DesktopShellActivity> mDesktop =
            new WeakReference<>(null);

    DesktopUiGateway(final DesktopSessionRegistry session) {
        mSession = session;
    }

    void registerShell(final DesktopShellActivity activity) {
        synchronized (mHostLock) {
            mShell = new WeakReference<>(activity);
        }
    }

    void registerDesktop(final DesktopShellActivity activity) {
        final DesktopShellActivity previous;
        final boolean replacingSameTask;
        final boolean previousWasLocal;
        final int displayId = activity.getCurrentDisplayId();
        synchronized (mHostLock) {
            previous = mDesktop.get();
            replacingSameTask = previous != null
                    && previous != activity
                    && previous.getTaskId() == activity.getTaskId();
            previousWasLocal = previous != null
                    && previous != activity
                    && previous.getCurrentDisplayId()
                            == Display.DEFAULT_DISPLAY;
            mDesktop = new WeakReference<>(activity);
            mSession.registerHost(
                    displayId, activity.getTaskId(), replacingSameTask);
        }
        if (previous != null && previous != activity) {
            // Nubia may move the phone task before the dedicated Console HOME
            // starts.
            Log.i(TAG, "replacing desktop shell task=" + previous.getTaskId()
                    + " with task=" + activity.getTaskId());
            previous.releaseDesktopOverlays();
            if (previous.getTaskId() != activity.getTaskId()
                    && !previous.isFinishing()) {
                previous.finishAndRemoveTask();
            }
        }
        final DesktopDisplayTarget target = sessionSnapshot().target();
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

    void unregister(final DesktopShellActivity activity) {
        final boolean changingConfigurations =
                activity.isChangingConfigurations();
        final int displayId = activity.getCurrentDisplayId();
        final boolean desktopRemoved;
        synchronized (mHostLock) {
            if (mShell.get() == activity) {
                mShell.clear();
            }
            desktopRemoved = mDesktop.get() == activity;
            if (desktopRemoved) {
                mDesktop.clear();
                mSession.unregisterHost(displayId, changingConfigurations);
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

    void closeDesktopSession(final int displayId) {
        final DesktopShellActivity activity;
        synchronized (mHostLock) {
            activity = usableDesktopLocked(false);
            final DesktopDisplayTarget target = mSession.snapshot().target();
            if (activity == null
                    || displayId < Display.DEFAULT_DISPLAY
                    || target == null
                    || target.displayId != displayId) {
                return;
            }
            mDesktop.clear();
            if (mShell.get() == activity) {
                mShell.clear();
            }
            mSession.close();
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
            mMainHandler.post(close);
        }
    }

    DesktopSessionSnapshot sessionSnapshot() {
        synchronized (mHostLock) {
            reconcileSessionHostLocked();
            return mSession.snapshot();
        }
    }

    void noteDesktopTarget(final DesktopDisplayTarget target) {
        if (target == null) {
            return;
        }
        synchronized (mHostLock) {
            mSession.noteTarget(target);
        }
    }

    void clearDesktopTarget(final DesktopDisplayTarget target) {
        synchronized (mHostLock) {
            mSession.clearTarget(target);
        }
    }

    DesktopViewport getDesktopViewport(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || activity.getCurrentDisplayId() != displayId) {
            return null;
        }
        return activity.getDesktopViewport();
    }

    Rect getDesktopWorkAreaBounds(final int displayId) {
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

    boolean showStart() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::showStartFromRuntime);
        return true;
    }

    boolean dispatchOverlayTextInput(
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

    boolean hasOverlayTextInput(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId
                || Looper.myLooper() != Looper.getMainLooper()) {
            return false;
        }
        final OverlayPanelController overlays = activity.overlayPanels();
        return overlays != null && overlays.hasTextInputTarget();
    }

    void showTransientStatus(
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

    void refreshDesktopControls() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity != null) {
            activity.runOnUiThread(activity::updateDesktopControls);
        }
    }

    boolean refreshDesktopInputFocus(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        activity.runOnUiThread(activity::refreshDesktopInputFocus);
        return true;
    }

    boolean restoreLastVisibleWindows() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::restoreLastVisibleWindows);
        return true;
    }

    boolean toggleDesktopWorkspace() {
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

    boolean recreateShellOnDisplay(final int displayId) {
        final DesktopShellActivity activity;
        synchronized (mHostLock) {
            activity = mShell.get();
        }
        if (!isUsable(activity)
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        mMainHandler.post(() -> {
            if (!activity.isActivityUnavailable()) {
                activity.recreate();
            }
        });
        return true;
    }

    boolean advanceAltTab(final boolean reverse) {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(() -> activity.advanceAltTab(reverse));
        return true;
    }

    boolean finishAltTab() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::finishAltTab);
        return true;
    }

    boolean cancelAltTab() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::cancelAltTabFromRuntime);
        return true;
    }

    boolean toggleShortcutHelp() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::toggleShortcutHelp);
        return true;
    }

    boolean toggleNotificationCenter() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity.notifications()::toggle);
        return true;
    }

    boolean toggleSystemPanel() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::toggleSystemPanel);
        return true;
    }

    boolean openSettings() {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::openSettings);
        return true;
    }

    void refreshSettings() {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity != null) {
            activity.runOnUiThread(activity::refreshSettings);
        }
    }

    boolean isDesktopReadyOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isDesktopHostReady();
    }

    boolean isDesktopWallpaperRendered(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isDesktopWallpaperRendered();
    }

    boolean isUsingFallbackDesktopWallpaper(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isUsingFallbackDesktopWallpaper();
    }

    int getDesktopHostIdentity(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                        && activity.getCurrentDisplayId() == displayId
                ? System.identityHashCode(activity) : 0;
    }

    boolean isDesktopWindowFocused(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.hasWindowFocus();
    }

    boolean focusDesktopOnDisplay(final int displayId) {
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

    void prepareTaskFocus(final int displayId, final int taskId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity != null
                && activity.getCurrentDisplayId() == displayId) {
            activity.setDesktopWindowFocusable(
                    taskId == activity.getTaskId());
        }
    }

    void syncTaskbarWithSnapshot(
            final int displayId,
            final TaskRepository.Snapshot snapshot) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || snapshot == null || !snapshot.available
                || activity.getCurrentDisplayId() != displayId) {
            return;
        }
        activity.runOnUiThread(
                () -> activity.syncTaskbarWithSnapshot(snapshot));
    }

    private DesktopShellActivity usableDesktop(final boolean requireOverlay) {
        synchronized (mHostLock) {
            return usableDesktopLocked(requireOverlay);
        }
    }

    private DesktopShellActivity usableDesktopLocked(
            final boolean requireOverlay) {
        final DesktopShellActivity activity = reconcileSessionHostLocked();
        if (!isUsable(activity) || !activity.isDesktopShell()
                || (requireOverlay && activity.overlayPanels() == null)) {
            return null;
        }
        return activity;
    }

    private DesktopShellActivity reconcileSessionHostLocked() {
        final DesktopShellActivity activity = mDesktop.get();
        if (!isUsable(activity) || !activity.isDesktopShell()) {
            final DesktopSessionSnapshot snapshot = mSession.snapshot();
            if (snapshot.hasHost()) {
                mSession.unregisterHost(
                        snapshot.activeDisplayId(), true);
            }
            return null;
        }
        mSession.observeHost(
                activity.getCurrentDisplayId(), activity.getTaskId());
        return activity;
    }

    private static boolean isUsable(final DesktopShellActivity activity) {
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }
}
