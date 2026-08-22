package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        recordSession("host_registered", displayId, activity.getTaskId());
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
        recordSession("host_unregistered", displayId, activity.getTaskId());
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
        final Runnable closeHost = () -> {
            activity.releaseDesktopOverlays();
            if (!activity.isFinishing()) {
                activity.finishAndRemoveTask();
            }
            MagicDeskRuntime.refreshDesktopTasks();
            if (displayId == Display.DEFAULT_DISPLAY) {
                MagicDeskRuntime.scheduleLocalDesktopCleanup();
            }
        };
        MagicDeskRuntime.releaseDesktopTaskSession(() -> {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                closeHost.run();
            } else {
                mMainHandler.post(closeHost);
            }
        });
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
        recordSession("target_selected", target.displayId, -1);
    }

    void clearDesktopTarget(final DesktopDisplayTarget target) {
        synchronized (mHostLock) {
            mSession.clearTarget(target);
        }
        if (target != null) {
            recordSession("target_cleared", target.displayId, -1);
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

    boolean launchDesktopShortcut(
            final DesktopApplicationShortcut shortcut,
            final DesktopLaunchArguments arguments,
            final String desktopFilePath,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.launchDesktopShortcut(
                        shortcut, arguments, desktopFilePath);
    }

    boolean launchDesktopWebShortcut(
            final DesktopWebShortcut shortcut,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.launchDesktopWebShortcut(shortcut);
    }

    boolean launchAutomationRequest(
            final DesktopLaunchRequest request,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || request == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        final boolean[] launched = new boolean[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            if (isUsable(activity)) {
                launched[0] = activity.launchAutomationRequest(request);
            }
            ready.countDown();
        });
        return await(ready) && launched[0];
    }

    boolean openFilesAt(final String path, final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || path == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        activity.runOnUiThread(() -> activity.openFilesAt(path));
        return true;
    }

    boolean launchApplication(
            final AppLaunchTarget target,
            final DesktopLaunchMode mode,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || target == null
                || mode == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        activity.runOnUiThread(() -> {
            if (activity.isActivityUnavailable()) {
                return;
            }
            final AppItem app = activity.findOrLoadApp(
                    activity.getLauncherApps(), target);
            if (app != null) {
                activity.launchForMode(app, mode, null);
            }
        });
        return true;
    }

    boolean invokeAppAction(
            final AppLaunchTarget target,
            final String actionId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || target == null
                || actionId == null || actionId.isEmpty()) {
            return false;
        }
        final boolean[] invoked = new boolean[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            if (isUsable(activity)) {
                final AppItem app = activity.findOrLoadApp(
                        activity.getLauncherApps(), target);
                for (final AppShortcutAction action
                        : new AppShortcutRepository(activity).load(app)) {
                    if (actionId.equals(action.id)) {
                        // Lookup is the acceptance boundary; native launch may
                        // block this UI callback while its task is prepared.
                        invoked[0] = true;
                        ready.countDown();
                        activity.launchShortcut(app, action);
                        return;
                    }
                }
            }
            ready.countDown();
        });
        return await(ready) && invoked[0];
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
                                : MagicDeskRuntime
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

    void setDesktopPlaneForeground(
            final int displayId,
            final boolean foreground) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (!isUsable(activity)
                || activity.getCurrentDisplayId() != displayId) {
            return;
        }
        mMainHandler.post(() -> {
            if (isUsable(activity)
                    && activity.getCurrentDisplayId() == displayId) {
                activity.setDesktopPlaneForeground(foreground);
            }
        });
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

    boolean openBuiltin(final String builtin) {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null || builtin == null) {
            return false;
        }
        final Runnable action;
        switch (builtin) {
            case "files":
                action = activity::openFiles;
                break;
            case "console":
                action = activity::openConsole;
                break;
            case "task_manager":
                action = activity::openTaskManager;
                break;
            case "settings":
                action = activity::openSettings;
                break;
            default:
                return false;
        }
        activity.runOnUiThread(action);
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

    boolean isTaskbarVisibleOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isTaskbarVisible();
    }

    DesktopUiSnapshot getAutomationUiSnapshot(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return DesktopUiSnapshot.UNAVAILABLE;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return activity.getAutomationUiSnapshot();
        }
        final DesktopUiSnapshot[] result = new DesktopUiSnapshot[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            if (isUsable(activity)
                    && activity.getCurrentDisplayId() == displayId) {
                result[0] = activity.getAutomationUiSnapshot();
            }
            ready.countDown();
        });
        try {
            if (!ready.await(2L, TimeUnit.SECONDS)) {
                return DesktopUiSnapshot.UNAVAILABLE;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return DesktopUiSnapshot.UNAVAILABLE;
        }
        return result[0] == null
                ? DesktopUiSnapshot.UNAVAILABLE : result[0];
    }

    boolean focusDesktopOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        final int taskId = activity.getTaskId();
        MagicDeskRuntime.focusDesktopTask(
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

    private static boolean await(final CountDownLatch ready) {
        try {
            return ready.await(2L, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void recordSession(
            final String operation,
            final int displayId,
            final int taskId) {
        try {
            final org.json.JSONObject data = new org.json.JSONObject()
                    .put("displayId", displayId);
            if (taskId >= 0) {
                data.put("taskId", taskId);
            }
            DesktopAutomationEventJournal.record(
                    "session",
                    operation,
                    true,
                    "display=" + displayId,
                    data);
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "session", operation, true, "display=" + displayId);
        }
    }
}
