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
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local gateway to the live desktop host Activity. */
final class DesktopUiGateway {
    private static final String TAG = "MagicDesk";
    private static final long APP_ACTION_COMPLETION_TIMEOUT_SECONDS = 10L;

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
        final int previousDisplayId;
        final DesktopSessionPolicy previousPolicy;
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
            previousDisplayId = previous == null
                    ? Display.INVALID_DISPLAY
                    : previous.getCurrentDisplayId();
            previousPolicy = mSession.snapshot().policy();
            mDesktop = new WeakReference<>(activity);
            mSession.registerHost(
                    displayId, activity.getTaskId(), replacingSameTask);
        }
        AppWindowStateStore.beginSession(
                sessionSnapshot().policy(), replacingSameTask);
        if (previous != null && previous != activity) {
            if (previousDisplayId >= Display.DEFAULT_DISPLAY
                    && previousDisplayId != displayId) {
                if (previousPolicy.persistWorkspace) {
                    MagicDeskRuntime.preserveDesktopTasks(previousDisplayId);
                }
            }
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
        final DesktopSessionPolicy policy;
        synchronized (mHostLock) {
            if (mShell.get() == activity) {
                mShell.clear();
            }
            desktopRemoved = mDesktop.get() == activity;
            policy = mSession.snapshot().policy();
            if (desktopRemoved) {
                mDesktop.clear();
                mSession.unregisterHost(displayId, changingConfigurations);
            }
        }
        if (!desktopRemoved || changingConfigurations) {
            return;
        }
        if (policy.persistWorkspace) {
            MagicDeskRuntime.preserveDesktopTasks(displayId);
        }
        MagicDeskRuntime.refreshDesktopTasks();
        MagicDeskRuntime.releaseDesktopTaskSession(() ->
                TaskCommandQueue.execute(
                        DesktopUiGateway::flushWindowSessionState));
        recordSession("host_unregistered", displayId, activity.getTaskId());
        if (displayId == Display.DEFAULT_DISPLAY) {
            MagicDeskRuntime.scheduleLocalDesktopCleanup();
        }
    }

    void closeDesktopSession(
            final int displayId,
            final Runnable completion) {
        final DesktopShellActivity activity;
        final DesktopSessionPolicy policy;
        synchronized (mHostLock) {
            activity = usableDesktopLocked(false);
            final DesktopDisplayTarget target = mSession.snapshot().target();
            if (activity == null
                    || displayId < Display.DEFAULT_DISPLAY
                    || target == null
                    || target.displayId != displayId) {
                if (completion != null) {
                    completion.run();
                }
                return;
            }
            policy = mSession.snapshot().policy();
            mDesktop.clear();
            if (mShell.get() == activity) {
                mShell.clear();
            }
            mSession.close();
        }
        if (policy.persistWorkspace) {
            MagicDeskRuntime.preserveDesktopTasks(displayId);
        }
        final AtomicInteger remainingCloseParts = new AtomicInteger(2);
        final Runnable closePartFinished = () -> {
            if (remainingCloseParts.decrementAndGet() == 0
                    && completion != null) {
                completion.run();
            }
        };
        final Runnable closeHost = () -> {
            try {
                activity.releaseDesktopOverlays();
                if (!activity.isFinishing()) {
                    activity.finishAndRemoveTask();
                }
                MagicDeskRuntime.refreshDesktopTasks();
                if (displayId == Display.DEFAULT_DISPLAY) {
                    MagicDeskRuntime.scheduleLocalDesktopCleanup();
                }
            } finally {
                closePartFinished.run();
            }
        };
        MagicDeskRuntime.releaseDesktopTaskSession(() -> {
            TaskCommandQueue.execute(() -> {
                try {
                    flushWindowSessionState();
                } finally {
                    closePartFinished.run();
                }
            });
            if (Looper.myLooper() == Looper.getMainLooper()) {
                closeHost.run();
            } else {
                mMainHandler.post(closeHost);
            }
        });
    }

    void prepareDesktopSessionRemoval(
            final int displayId,
            final Runnable completion) {
        final DesktopDisplayTarget target;
        synchronized (mHostLock) {
            target = mSession.snapshot().target();
            if (usableDesktopLocked(false) == null
                    || target == null
                    || target.displayId != displayId) {
                if (completion != null) {
                    completion.run();
                }
                return;
            }
        }
        // Keep the task topology alive until Android reports that the display
        // is gone. Releasing it here empties the display before the platform
        // removal transition and can strand per-display framework state.
        TaskCommandQueue.execute(() -> {
            flushWindowSessionState();
            if (completion != null) {
                completion.run();
            }
        });
    }

    void resumeDesktopSessionAfterFailedRemoval(final int displayId) {
        synchronized (mHostLock) {
            final DesktopDisplayTarget target = mSession.snapshot().target();
            if (usableDesktopLocked(false) == null
                    || target == null
                    || target.displayId != displayId) {
                return;
            }
        }
        AppWindowStateStore.beginSession(
                sessionSnapshot().policy(), false);
        MagicDeskRuntime.refreshDesktopTasks();
    }

    private static void flushWindowSessionState() {
        if (!AppWindowStateStore.endSession()) {
            Log.w(TAG, "Could not flush desktop window session state");
        }
    }

    DesktopSessionSnapshot sessionSnapshot() {
        synchronized (mHostLock) {
            reconcileSessionHostLocked();
            return mSession.snapshot();
        }
    }

    void noteDesktopTarget(final DesktopDisplayTarget target) {
        noteDesktopTarget(target, DesktopSessionPolicy.USER);
    }

    void noteDesktopTarget(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (target == null) {
            return;
        }
        synchronized (mHostLock) {
            mSession.noteTarget(target, policy);
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

    Rect getDesktopTaskbarBounds(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null || activity.getCurrentDisplayId() != displayId) {
            return null;
        }
        final DesktopTaskbarHost taskbarHost = activity.taskbarHost();
        return taskbarHost == null
                ? activity.getTaskbarBounds() : taskbarHost.appliedBounds();
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
            final RelativeWindowBounds preferredBounds,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || target == null
                || mode == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        final boolean[] launched = new boolean[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            if (isUsable(activity) && !activity.isActivityUnavailable()) {
                final AppItem app = activity.findOrLoadApp(
                        activity.getLauncherApps(), target);
                if (app != null) {
                    activity.launchForMode(
                            app, mode, preferredBounds, null);
                    launched[0] = true;
                }
            }
            ready.countDown();
        });
        return await(ready) && launched[0];
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
                        : new AppShortcutRepository(activity).loadAll(target)) {
                    if (actionId.equals(action.id)) {
                        activity.launchShortcut(
                                app,
                                action,
                                success -> {
                                    invoked[0] = success;
                                    ready.countDown();
                                });
                        return;
                    }
                }
            }
            ready.countDown();
        });
        return await(ready, APP_ACTION_COMPLETION_TIMEOUT_SECONDS)
                && invoked[0];
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

    boolean refreshDesktopInputFocus(
            final int displayId,
            final int focusedTaskId) {
        return refreshDesktopInputFocus(displayId, focusedTaskId, null);
    }

    boolean refreshDesktopInputFocus(
            final int displayId,
            final int focusedTaskId,
            final Runnable completion) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            if (completion != null) {
                completion.run();
            }
            return false;
        }
        activity.runOnUiThread(() -> {
            activity.setDesktopWindowFocusable(
                    focusedTaskId == activity.getTaskId());
            activity.refreshDesktopInputFocus(completion);
        });
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
        return toggleDesktopWorkspace(null);
    }

    boolean toggleDesktopWorkspace(
            final TaskRepository.ActionCallback callback) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null) {
            return false;
        }
        final int displayId = activity.getCurrentDisplayId();
        activity.runOnUiThread(() -> {
            if (!isUsable(activity)) {
                completeTaskAction(
                        callback, false, "desktop UI is unavailable");
                return;
            }
            activity.hideAllPanels();
            activity.setTaskbarVisible(true);
            toggleShowDesktopWorkspaceOnDisplay(displayId, callback);
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

    void setSystemDialogVisible(
            final int displayId,
            final boolean visible) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (!isUsable(activity)
                || activity.getCurrentDisplayId() != displayId) {
            return;
        }
        mMainHandler.post(() -> {
            if (isUsable(activity)
                    && activity.getCurrentDisplayId() == displayId) {
                activity.setSystemDialogVisible(visible);
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
            case "diagnostics":
                action = activity::openDiagnostics;
                break;
            default:
                return false;
        }
        activity.runOnUiThread(action);
        return true;
    }

    boolean openConsole(
            final String directory,
            final String command,
            final String terminalId,
            final DesktopExecBackend backend) {
        final DesktopShellActivity activity = usableDesktop(true);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(() -> activity.openConsole(
                directory, command, terminalId, backend));
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

    DesktopAutomationUiRegistry.Snapshot getAutomationUiElements(
            final int displayId,
            final String query,
            final boolean includeHidden) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return DesktopAutomationUiRegistry.Snapshot.UNAVAILABLE;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return activity.getAutomationUiElements(query, includeHidden);
            } catch (org.json.JSONException error) {
                return DesktopAutomationUiRegistry.Snapshot.UNAVAILABLE;
            }
        }
        final DesktopAutomationUiRegistry.Snapshot[] result =
                new DesktopAutomationUiRegistry.Snapshot[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            try {
                if (isUsable(activity)
                        && activity.getCurrentDisplayId() == displayId) {
                    result[0] = activity.getAutomationUiElements(
                            query, includeHidden);
                }
            } catch (org.json.JSONException ignored) {
            } finally {
                ready.countDown();
            }
        });
        if (!await(ready) || result[0] == null) {
            return DesktopAutomationUiRegistry.Snapshot.UNAVAILABLE;
        }
        return result[0];
    }

    DesktopAutomationUiRegistry.ActionResult invokeAutomationUiAction(
            final int displayId,
            final String elementId,
            final String action) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return new DesktopAutomationUiRegistry.ActionResult(
                    false, "desktop UI is unavailable", null);
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return activity.invokeAutomationUiAction(elementId, action);
            } catch (org.json.JSONException error) {
                return new DesktopAutomationUiRegistry.ActionResult(
                        false, ShellAccess.usefulMessage(error), null);
            }
        }
        final DesktopAutomationUiRegistry.ActionResult[] result =
                new DesktopAutomationUiRegistry.ActionResult[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            try {
                if (isUsable(activity)
                        && activity.getCurrentDisplayId() == displayId) {
                    result[0] = activity.invokeAutomationUiAction(
                            elementId, action);
                }
            } catch (org.json.JSONException error) {
                result[0] = new DesktopAutomationUiRegistry.ActionResult(
                        false, ShellAccess.usefulMessage(error), null);
            } finally {
                ready.countDown();
            }
        });
        if (!await(ready) || result[0] == null) {
            return new DesktopAutomationUiRegistry.ActionResult(
                    false, "desktop UI action timed out", null);
        }
        return result[0];
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

    private boolean toggleShowDesktopWorkspaceOnDisplay(
            final int displayId,
            final TaskRepository.ActionCallback callback) {
        final DesktopShellActivity activity = usableDesktop(false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            completeTaskAction(callback, false, "desktop UI is unavailable");
            return false;
        }
        final int taskId = activity.getTaskId();
        MagicDeskRuntime.toggleShowDesktopWorkspace(
                displayId,
                taskId,
                result -> {
                    if (!result.success) {
                        Log.w(TAG, "Could not toggle desktop workspace task="
                                + taskId + " display=" + displayId
                                + " result=" + result.message);
                    }
                    completeTaskAction(
                            callback, result.success, result.message);
                });
        return true;
    }

    private static void completeTaskAction(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(new TaskRepository.ActionResult(
                    success, message));
        }
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
        return await(ready, 2L);
    }

    private static boolean await(
            final CountDownLatch ready,
            final long timeoutSeconds) {
        try {
            return ready.await(timeoutSeconds, TimeUnit.SECONDS);
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
