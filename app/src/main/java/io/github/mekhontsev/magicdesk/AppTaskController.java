package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.content.Intent;
import android.util.Log;

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
        Log.i(TAG, "launch default package=" + app.packageName
                + " canFloat=" + app.canFloat
                + " fullscreenReason=" + app.fullscreenReason
                + " display=" + mActivity.getCurrentDisplayId());
        if (app.canFloat
                && AppItem.FULLSCREEN_REASON_NONE.equals(
                        app.fullscreenReason)) {
            launchFloating(app, false);
        } else {
            launchFullscreen(app);
        }
    }

    void launchFloating(final AppItem app, final boolean rootColdLaunch) {
        Log.i(TAG, "launch floating package=" + app.packageName
                + " display=" + mActivity.getCurrentDisplayId());
        mActivity.setTaskbarVisible(true);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_window, app.label));
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        try {
            final Intent intent = FreeformLauncherActivity.createIntent(
                    mActivity,
                    app.packageName,
                    getTaskIds(visibleTasks),
                    rootColdLaunch);
            final ActivityOptions options = ActivityOptions.makeBasic();
            DesktopShellActivity.invokeIntOption(
                    options,
                    "setLaunchDisplayId",
                    mActivity.getCurrentDisplayId());
            mActivity.startActivity(intent, options.toBundle());
        } catch (RuntimeException e) {
            TaskRepository.bringStackToFront(
                    visibleTasks, null, null);
            mActivity.showLaunchFailure(e);
        }
    }

    void launchFullscreen(final AppItem app) {
        Log.i(TAG, "launch fullscreen package=" + app.packageName
                + " display=" + mActivity.getCurrentDisplayId());
        final int displayId =
                beginFullscreenTransition(app.packageName);
        mActivity.setTaskbarVisible(false);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_fullscreen, app.label));
        try {
            if (RuntimeAccess.has(
                    RuntimeAccess.Capability.TASK_CONTROL)) {
                final ExistingTaskController.ReuseResult reuseResult =
                        ExistingTaskController.reuseIfExists(
                                app.packageName,
                                mActivity.getCurrentDisplayId(),
                                false);
                if (reuseResult.found) {
                    DesktopTaskController.finishFullscreenTransition(
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
            final Intent launchIntent = mActivity.getPackageManager()
                    .getLaunchIntentForPackage(app.packageName);
            if (launchIntent == null) {
                DesktopTaskController.finishFullscreenTransition(
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
            final ActivityOptions options = ActivityOptions.makeBasic();
            DesktopShellActivity.invokeIntOption(
                    options,
                    "setLaunchDisplayId",
                    mActivity.getCurrentDisplayId());
            mActivity.startActivity(
                    launchIntent, options.toBundle());
            DesktopTaskController.finishFullscreenTransition(
                    displayId, true);
        } catch (IOException e) {
            DesktopTaskController.finishFullscreenTransition(
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
            DesktopTaskController.finishFullscreenTransition(
                    displayId, false);
            mActivity.setTaskbarVisible(true);
            mActivity.showLaunchFailure(e);
        }
    }

    void focusTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        mActivity.setStatus(mActivity.getString(
                R.string.status_switching_to, app.label));
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int displayId = mActivity.getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot ->
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()
                            || displayId
                                    != mActivity.getCurrentDisplayId()) {
                        return;
                    }
                    if (!snapshot.rootAvailable) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_switch_failed,
                                snapshot.error.length() == 0
                                        ? app.label
                                        : snapshot.error));
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
                        return;
                    }
                    DesktopTaskController.focusStack(
                            visibleTasks,
                            currentTask,
                            result -> mActivity.runOnUiThread(() -> {
                                if (!result.success) {
                                    mActivity.setStatus(
                                            mActivity.getString(
                                                    R.string.status_switch_failed,
                                                    result.message.length() == 0
                                                            ? app.label
                                                            : result.message));
                                    return;
                                }
                                mActivity.setTaskbarVisible(
                                        currentTask.isFreeform());
                                mActivity.refreshTaskSnapshot();
                            }));
                }));
    }

    void openTaskFullscreen(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final int displayId =
                beginFullscreenTransition(task.taskId);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_fullscreen, app.label));
        TaskRepository.setFullscreen(task, result -> {
            DesktopTaskController.finishFullscreenTransition(
                    displayId, result.success);
            mActivity.runOnUiThread(() -> {
                if (result.success) {
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

    void closeTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_closing_window, app.label));
        TaskRepository.closeTask(
                task,
                result -> mActivity.runOnUiThread(() -> {
                    mActivity.setStatus(mActivity.getString(
                            result.success
                                    ? R.string.status_window_closed
                                    : R.string.status_close_window_failed,
                            result.success
                                    ? app.label
                                    : result.message));
                    mActivity.refreshTaskSnapshot();
                }));
    }

    void forceStop(final AppItem app) {
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_force_stopping, app.label));
        TaskRepository.forceStop(
                app.packageName,
                result -> mActivity.runOnUiThread(() -> {
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
                DesktopTaskController.getLastVisibleFreeformTasks(
                        displayId);
        if (savedTasks.isEmpty()) {
            mActivity.setStatus(R.string.status_desktop_visible);
            TaskRepository.load(
                    displayId,
                    snapshot -> mActivity.runOnUiThread(() ->
                            mActivity.restoreWorkspaceApp(
                                    snapshot, true)));
            return;
        }
        mActivity.setStatus(R.string.status_restoring_windows);
        TaskRepository.restoreFreeformStack(
                displayId,
                savedTasks,
                result -> mActivity.runOnUiThread(() -> {
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
                            snapshot -> mActivity.runOnUiThread(() ->
                                    mActivity.restoreWorkspaceApp(
                                            snapshot, false)));
                }));
    }

    private int beginFullscreenTransition(final int excludedTaskId) {
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int displayId = mActivity.getCurrentDisplayId();
        DesktopTaskController.beginFullscreenTransition(
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
        DesktopTaskController.beginFullscreenTransition(
                displayId, visibleTasks, excludedTaskId);
        return displayId;
    }

    private List<TaskRepository.TaskEntry>
            captureVisibleFreeformTasks() {
        final List<TaskRepository.TaskEntry> watchedTasks =
                DesktopTaskController.getVisibleFreeformTasks(
                        mActivity.getCurrentDisplayId());
        return watchedTasks == null
                ? getVisibleFreeformTasks(mActivity.getTaskSnapshot())
                : watchedTasks;
    }

    private List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final TaskRepository.Snapshot snapshot) {
        if (snapshot == null || !snapshot.rootAvailable) {
            return Collections.emptyList();
        }
        final List<TaskRepository.TaskEntry> visibleTasks =
                new ArrayList<>();
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.visible
                    && task.isFreeform()
                    && !task.home
                    && !mActivity.getPackageName().equals(
                            task.packageName)) {
                visibleTasks.add(task);
            }
        }
        return visibleTasks;
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
