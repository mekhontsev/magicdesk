package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.content.Intent;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AppTaskController {
    private static final String TAG = "MagicDesk";

    private final DesktopShellActivity mActivity;
    private List<TaskRepository.TaskEntry> mInteractionVisibleTasks =
            Collections.emptyList();

    AppTaskController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void clearInteractionStack() {
        mInteractionVisibleTasks = Collections.emptyList();
    }

    void captureInteractionStackForPanel() {
        if (!mActivity.hasVisiblePanel()) {
            mInteractionVisibleTasks = captureVisibleFreeformTasks();
        }
    }

    void launchDefault(final AppItem app) {
        final AppWindowState saved = remembersWindowState(app)
                ? AppWindowStateStore.load(app.packageName) : null;
        Log.i(TAG, "launch default package=" + app.packageName
                + " canFloat=" + app.canFloat
                + " fullscreenReason=" + app.fullscreenReason
                + " display=" + mActivity.getCurrentDisplayId());
        if (saved != null
                && saved.mode == AppWindowState.Mode.WINDOWED
                && canControlWindowing()) {
            launchFloating(
                    app,
                    true,
                    saved.windowBounds,
                    WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING);
        } else if (saved != null
                && saved.mode == AppWindowState.Mode.FULLSCREEN) {
            launchFullscreen(app, false);
        } else if (canControlWindowing()
                && app.canFloat
                && AppItem.FULLSCREEN_REASON_NONE.equals(
                        app.fullscreenReason)) {
            launchFloating(app);
        } else {
            launchFullscreen(app, false);
        }
    }

    void launchFloating(final AppItem app) {
        launchFloating(app, false);
    }

    void launchWindowed(final AppItem app) {
        launchFloating(app, true);
    }

    void launchNewWindow(final AppItem app) {
        if (!BuiltInDesktopAppCatalog.supportsMultipleWindows(
                app.launchTarget)) {
            launchFloating(app, true);
            return;
        }
        launchFloating(
                app,
                true,
                null,
                WindowedAppLauncher.TaskReusePolicy.CREATE_NEW);
    }

    void launchInternalWindow(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final String label) {
        final int displayId = mActivity.getCurrentDisplayId();
        final boolean multipleWindows =
                BuiltInDesktopAppCatalog.supportsMultipleWindows(launchTarget);
        if (!canControlWindowing()) {
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (multipleWindows) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            } else {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
            mActivity.startActivity(launchIntent, options.toBundle());
            return;
        }

        launchWindow(
                launchIntent,
                launchTarget,
                label,
                true,
                BuiltInDesktopAppCatalog.defaultWindowBounds(launchTarget),
                multipleWindows
                        ? WindowedAppLauncher.TaskReusePolicy.CREATE_NEW
                        : WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                null);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed) {
        final AppWindowState saved = remembersWindowState(app)
                ? AppWindowStateStore.load(app.packageName) : null;
        launchFloating(
                app,
                explicitWindowed,
                saved == null ? null : saved.windowBounds,
                WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy) {
        if (!canControlWindowing()) {
            launchFullscreen(app, false);
            return;
        }
        final TaskRepository.TaskEntry existingTask =
                mActivity.findFirstTask(app.launchTarget);
        if (reusePolicy == WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING
                && existingTask != null
                && existingTask.displayId == mActivity.getCurrentDisplayId()
                && existingTask.isFreeform()) {
            if (explicitWindowed && remembersWindowState(app)) {
                AppWindowStateStore.rememberMode(
                        app.packageName,
                        AppWindowState.Mode.WINDOWED);
            }
            focusTask(app, existingTask);
            return;
        }
        final Intent launchIntent = app.launchTarget.resolve(
                mActivity.getPackageManager());
        if (launchIntent == null) {
            final List<TaskRepository.TaskEntry> visibleTasks =
                    takeInteractionVisibleTasks();
            TaskRepository.bringStackToFront(visibleTasks, null, null);
            mActivity.setErrorStatus(
                    "APP-LAUNCH-002",
                    mActivity.getString(
                            R.string.status_launch_failed,
                            "no launcher activity"),
                    "package=" + app.packageName,
                    null);
            return;
        }
        Log.i(TAG, "launch floating package=" + app.packageName
                + " display=" + mActivity.getCurrentDisplayId()
                + " explicitWindowed=" + explicitWindowed);
        launchWindow(
                launchIntent,
                app.launchTarget,
                app.label,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                () -> {
                    if (explicitWindowed && remembersWindowState(app)) {
                        AppWindowStateStore.rememberMode(
                                app.packageName,
                                AppWindowState.Mode.WINDOWED);
                    }
                });
    }

    private void launchWindow(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final String label,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy,
            final Runnable afterLaunch) {
        mActivity.setTaskbarVisible(true);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_window, label));
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int displayId = mActivity.getCurrentDisplayId();
        TaskCommandQueue.execute(() -> {
            try {
                WindowedAppLauncher.launch(
                        launchIntent,
                        launchTarget,
                        displayId,
                        getTaskIds(visibleTasks),
                        explicitWindowed,
                        preferredBounds,
                        reusePolicy,
                        () -> publishConfirmedLaunchSnapshot(displayId));
                if (afterLaunch != null) {
                    afterLaunch.run();
                }
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mActivity.setStatus(mActivity.getString(
                            R.string.status_switch_done, label));
                    mActivity.refreshTaskSnapshot();
                });
            } catch (IOException | RuntimeException error) {
                TaskRepository.bringStackToFront(
                        visibleTasks, null, null);
                mActivity.runOnUiThread(() -> {
                    if (!mActivity.isActivityUnavailable()) {
                        mActivity.showLaunchFailure(error);
                    }
                });
            }
        });
    }

    private void publishConfirmedLaunchSnapshot(final int displayId) {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(displayId);
        if (!snapshot.available) {
            return;
        }
        mActivity.runOnUiThread(() -> {
            if (!mActivity.isActivityUnavailable()
                    && displayId == mActivity.getCurrentDisplayId()) {
                mActivity.syncTaskbarWithSnapshot(snapshot);
            }
        });
    }

    void launchFullscreen(final AppItem app) {
        launchFullscreen(app, true);
    }

    private void launchFullscreen(
            final AppItem app,
            final boolean rememberMode) {
        Log.i(TAG, "launch fullscreen package=" + app.packageName
                + " display=" + mActivity.getCurrentDisplayId());
        final int displayId =
                beginFullscreenTransition(app.packageName);
        mActivity.setTaskbarVisible(false);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_fullscreen, app.label));
        try {
            if (ShellAccess.isReady()) {
                final ExistingTaskController.ReuseResult reuseResult =
                        ExistingTaskController.reuseIfExists(
                                app.launchTarget,
                                mActivity.getCurrentDisplayId(),
                                false);
                if (reuseResult.found) {
                    if (rememberMode && remembersWindowState(app)) {
                        AppWindowStateStore.rememberMode(
                                app.packageName,
                                AppWindowState.Mode.FULLSCREEN);
                    }
                    MagicDeskRuntime.finishFullscreenTransition(
                            displayId, true);
                    Log.i(TAG,
                            "reused fullscreen package="
                                    + app.packageName);
                    mActivity.setStatus(mActivity.getString(
                            R.string.status_switch_done, app.label));
                    return;
                }
            }

            Log.i(TAG,
                    "fresh fullscreen launch package="
                            + app.packageName);
            final Intent launchIntent = app.launchTarget.resolve(
                    mActivity.getPackageManager());
            if (launchIntent == null) {
                MagicDeskRuntime.finishFullscreenTransition(
                        displayId, false);
                mActivity.setTaskbarVisible(true);
                mActivity.setErrorStatus(
                        "APP-LAUNCH-002",
                        mActivity.getString(
                                R.string.status_launch_failed,
                                "no launcher activity"),
                        "package=" + app.packageName,
                        null);
                return;
            }
            launchIntent.addFlags(getFullscreenLaunchFlags());
            FullscreenAppLauncher.launch(
                    launchIntent, mActivity.getCurrentDisplayId());
            ExistingTaskController.normalizeLaunchedFullscreen(
                    app.launchTarget,
                    mActivity.getCurrentDisplayId());
            if (rememberMode && remembersWindowState(app)) {
                AppWindowStateStore.rememberMode(
                        app.packageName,
                        AppWindowState.Mode.FULLSCREEN);
            }
            MagicDeskRuntime.finishFullscreenTransition(
                    displayId, true);
        } catch (IOException e) {
            MagicDeskRuntime.finishFullscreenTransition(
                    displayId, false);
            mActivity.setTaskbarVisible(true);
            mActivity.setErrorStatus(
                    "TASK-FULLSCREEN-001",
                    mActivity.getString(
                            R.string.status_switch_failed,
                            e.getMessage()),
                    "package=" + app.packageName
                            + " display=" + displayId,
                    e);
        } catch (RuntimeException e) {
            MagicDeskRuntime.finishFullscreenTransition(
                    displayId, false);
            mActivity.setTaskbarVisible(true);
            mActivity.showLaunchFailure(e);
        }
    }

    private static boolean canControlWindowing() {
        return ShellAccess.isReady();
    }

    void focusTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        focusTask(app, task, null, null);
    }

    void focusTask(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final List<TaskRepository.TaskEntry> focusStack,
            final Runnable completion) {
        mActivity.setStatus(mActivity.getString(
                R.string.status_switching_to, app.label));
        final List<TaskRepository.TaskEntry> visibleTasks;
        if (focusStack == null) {
            visibleTasks = takeInteractionVisibleTasks();
        } else {
            visibleTasks = new ArrayList<>(focusStack);
            mInteractionVisibleTasks = Collections.emptyList();
        }
        final int displayId = mActivity.getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot ->
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    if (displayId != mActivity.getCurrentDisplayId()) {
                        runCompletion(completion);
                        return;
                    }
                    if (!snapshot.available) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_switch_failed,
                                snapshot.error.length() == 0
                                        ? app.label
                                        : snapshot.error));
                        runCompletion(completion);
                        return;
                    }
                    mActivity.setTaskSnapshot(snapshot);
                    final TaskRepository.TaskEntry currentTask =
                            DesktopShellActivity.findTask(
                                    snapshot, task.taskId);
                    if (currentTask == null) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_switch_failed,
                                app.label));
                        mActivity.refreshTaskSnapshot();
                        runCompletion(completion);
                        return;
                    }
                    MagicDeskRuntime.focusStack(
                            visibleTasks,
                            currentTask,
                            result -> mActivity.runOnUiThread(() -> {
                                if (mActivity.isActivityUnavailable()) {
                                    return;
                                }
                                if (!result.success) {
                                    mActivity.setStatus(
                                            mActivity.getString(
                                                    R.string.status_switch_failed,
                                                    result.message.length() == 0
                                                            ? app.label
                                                            : result.message));
                                    runCompletion(completion);
                                    return;
                                }
                                mActivity.setTaskbarVisible(
                                        currentTask.isFreeform());
                                mActivity.refreshTaskSnapshot();
                                runCompletion(completion);
                            }));
                }));
    }

    private static void runCompletion(final Runnable completion) {
        if (completion != null) {
            completion.run();
        }
    }

    void toggleTaskbarTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        if (!task.active || !task.isFreeform()) {
            focusTask(app, task);
            return;
        }
        final TaskRepository.Snapshot snapshot = mActivity.getTaskSnapshot();
        final TaskRepository.TaskEntry nextTask =
                findNextVisibleTask(task.taskId, snapshot);
        final TaskRepository.TaskEntry desktopTask =
                findDesktopHostTask(snapshot);
        final TaskRepository.TaskEntry focusTask =
                nextTask != null ? nextTask : desktopTask;
        TaskRepository.minimizeTask(
                task, focusTask,
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    if (!result.success) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_switch_failed,
                                result.message.length() == 0
                                        ? app.label
                                        : result.message));
                        mActivity.refreshTaskSnapshot();
                        return;
                    }
                    mActivity.refreshTaskSnapshot();
                }));
    }

    private TaskRepository.TaskEntry findNextVisibleTask(
            final int excludedTaskId,
            final TaskRepository.Snapshot snapshot) {
        if (snapshot == null || !snapshot.available) {
            return null;
        }
        for (final TaskRepository.TaskEntry candidate : snapshot.tasks) {
            if (DesktopTaskController.isDesktopHostTask(candidate)) {
                break;
            }
            if (candidate != null
                    && candidate.taskId != excludedTaskId
                    && candidate.visible
                    && candidate.isFreeform()
                    && !candidate.home
                    && !mActivity.getPackageName().equals(
                            candidate.packageName)) {
                return candidate;
            }
        }
        return null;
    }

    private TaskRepository.TaskEntry findDesktopHostTask(
            final TaskRepository.Snapshot snapshot) {
        if (snapshot == null || !snapshot.available) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (DesktopTaskController.isDesktopHostTask(task)) {
                return task;
            }
        }
        return null;
    }

    void openTaskFullscreen(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        rememberWindowBounds(task);
        final int displayId =
                beginFullscreenTransition(task.taskId);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_fullscreen, app.label));
        TaskRepository.setFullscreen(
                task,
                result -> {
                    MagicDeskRuntime.finishFullscreenTransition(
                            displayId, result.success);
                    mActivity.runOnUiThread(() -> {
                        if (mActivity.isActivityUnavailable()) {
                            return;
                        }
                        if (result.success) {
                            if (remembersWindowState(app)) {
                                AppWindowStateStore.rememberMode(
                                        app.packageName,
                                        AppWindowState.Mode.FULLSCREEN);
                            }
                            mActivity.setTaskbarVisible(false);
                        }
                        mActivity.setStatus(mActivity.getString(
                                result.success
                                        ? R.string.status_switch_done
                                        : R.string.status_switch_failed,
                                result.success
                                        ? app.label
                                        : (result.message.length() == 0
                                                ? app.label
                                                : result.message)));
                        mActivity.refreshTaskSnapshot();
                    });
                });
    }

    private void rememberWindowBounds(
            final TaskRepository.TaskEntry task) {
        if (task == null
                || !task.isBoundedFreeform()
                || !BuiltInDesktopAppCatalog.remembersWindowState(task)) {
            return;
        }
        try {
            final RelativeWindowBounds bounds = RelativeWindowBounds.from(
                    task.bounds,
                    FloatingWindowController.getWorkAreaBounds(
                            task.displayId));
            if (bounds != null) {
                AppWindowStateStore.rememberWindowBounds(
                        Collections.singletonMap(task.packageName, bounds));
            }
        } catch (IOException ignored) {
            // The runtime task observer will capture the bounds when available.
        }
    }

    int getOtherDisplayId(final TaskRepository.TaskEntry task) {
        if (task == null) {
            return -1;
        }
        if (task.displayId != Display.DEFAULT_DISPLAY) {
            return Display.DEFAULT_DISPLAY;
        }
        final int externalDisplayId = PlatformDrivers.current()
                .projection().activeDesktopDisplayId(mActivity);
        return externalDisplayId > 0 ? externalDisplayId : -1;
    }

    void moveTaskToOtherDisplay(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final int targetDisplayId = getOtherDisplayId(task);
        if (targetDisplayId < 0) {
            return;
        }
        rememberWindowBounds(task);
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_moving_to_display,
                app.label,
                Integer.valueOf(targetDisplayId)));
        TaskRepository.moveTaskToDisplay(
                task,
                targetDisplayId,
                savedWindowBounds(app),
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    if (result.success) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_moved_to_display,
                                app.label,
                                Integer.valueOf(targetDisplayId)));
                    } else {
                        mActivity.setErrorStatus(
                                "TASK-DISPLAY-001",
                                mActivity.getString(
                                        R.string.status_move_to_display_failed,
                                        result.message));
                    }
                    mActivity.refreshTaskSnapshot();
                    MagicDeskRuntime
                            .refreshDesktopTasks();
                }));
    }

    private static RelativeWindowBounds savedWindowBounds(
            final AppItem app) {
        if (!remembersWindowState(app)) {
            return null;
        }
        final AppWindowState state =
                AppWindowStateStore.load(app.packageName);
        return state == null ? null : state.windowBounds;
    }

    private static boolean remembersWindowState(final AppItem app) {
        return app != null
                && BuiltInDesktopAppCatalog.remembersWindowState(
                        app.launchTarget);
    }

    void closeTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_closing_window, app.label));
        final TaskRepository.ActionCallback callback =
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mActivity.setStatus(mActivity.getString(
                            result.success
                                    ? R.string.status_window_closed
                                    : R.string.status_close_window_failed,
                            result.success
                                    ? app.label
                                    : result.message));
                    mActivity.refreshTaskSnapshot();
                });
        MagicDeskRuntime.closeTask(task, callback);
    }

    void forceStop(final AppItem app) {
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_force_stopping, app.label));
        TaskRepository.forceStop(
                app.packageName,
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mActivity.setStatus(mActivity.getString(
                            result.success
                                    ? R.string.status_app_force_stopped
                                    : R.string.status_force_stop_failed,
                            result.success
                                    ? app.label
                                    : result.message));
                    mActivity.refreshTaskSnapshot();
                }));
    }

    void restoreLastVisibleWindows() {
        mActivity.hideAllPanels();
        mActivity.setTaskbarVisible(true);
        clearInteractionStack();
        final int displayId = mActivity.getCurrentDisplayId();
        final List<TaskRepository.TaskEntry> savedTasks =
                MagicDeskRuntime.getLastVisibleFreeformTasks(
                        displayId);
        if (savedTasks.isEmpty()) {
            mActivity.setStatus(R.string.status_desktop_visible);
            TaskRepository.load(
                    displayId,
                    snapshot -> mActivity.runOnUiThread(() -> {
                        if (!mActivity.isActivityUnavailable()) {
                            mActivity.restoreWorkspaceApp(snapshot, true);
                        }
                    }));
            return;
        }
        mActivity.setStatus(R.string.status_restoring_windows);
        TaskRepository.restoreFreeformStack(
                displayId,
                savedTasks,
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mActivity.setStatus(result.success
                            ? mActivity.getString(
                                    R.string.status_windows_restored)
                            : mActivity.getString(
                                    R.string.status_switch_failed,
                                    result.message.length() == 0
                                            ? mActivity.getString(
                                                    R.string.status_restoring_windows)
                                            : result.message));
                    mActivity.refreshTaskSnapshot();
                    TaskRepository.load(
                            displayId,
                            snapshot -> mActivity.runOnUiThread(() -> {
                                if (!mActivity.isActivityUnavailable()) {
                                    mActivity.restoreWorkspaceApp(
                                            snapshot, false);
                                }
                            }));
                }));
    }

    private int beginFullscreenTransition(final int excludedTaskId) {
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int displayId = mActivity.getCurrentDisplayId();
        MagicDeskRuntime.beginFullscreenTransition(
                displayId, visibleTasks, excludedTaskId);
        return displayId;
    }

    private int beginFullscreenTransition(final String packageName) {
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        int excludedTaskId = -1;
        for (final TaskRepository.TaskEntry task : visibleTasks) {
            if (packageName.equals(task.packageName)) {
                excludedTaskId = task.taskId;
                break;
            }
        }
        final int displayId = mActivity.getCurrentDisplayId();
        MagicDeskRuntime.beginFullscreenTransition(
                displayId, visibleTasks, excludedTaskId);
        return displayId;
    }

    private List<TaskRepository.TaskEntry>
            captureVisibleFreeformTasks() {
        final List<TaskRepository.TaskEntry> watchedTasks =
                MagicDeskRuntime.getVisibleFreeformTasks(
                        mActivity.getCurrentDisplayId());
        return watchedTasks == null
                ? getVisibleFreeformTasks(mActivity.getTaskSnapshot())
                : watchedTasks;
    }

    private List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final TaskRepository.Snapshot snapshot) {
        return DesktopTaskController.selectVisibleFreeformTasks(snapshot);
    }

    private List<TaskRepository.TaskEntry>
            takeInteractionVisibleTasks() {
        final List<TaskRepository.TaskEntry> visibleTasks =
                mInteractionVisibleTasks.isEmpty()
                        ? captureVisibleFreeformTasks()
                        : new ArrayList<>(mInteractionVisibleTasks);
        mInteractionVisibleTasks = Collections.emptyList();
        return visibleTasks;
    }

    private static int[] getTaskIds(
            final List<TaskRepository.TaskEntry> tasks) {
        final int[] taskIds =
                new int[tasks == null ? 0 : tasks.size()];
        for (int index = 0; index < taskIds.length; index++) {
            taskIds[index] = tasks.get(index).taskId;
        }
        return taskIds;
    }

    private static int getFullscreenLaunchFlags() {
        return Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
    }
}
