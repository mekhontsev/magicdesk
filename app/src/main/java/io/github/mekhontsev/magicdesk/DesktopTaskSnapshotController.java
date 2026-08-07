package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;

/** Owns the current task snapshot and serialized asynchronous refreshes. */
final class DesktopTaskSnapshotController {
    private final DesktopShellActivity mActivity;
    private final WorkspaceAppController mWorkspace;

    private TaskRepository.Snapshot mSnapshot = new TaskRepository.Snapshot(
            java.util.Collections.<TaskRepository.TaskEntry>emptyList(),
            false,
            "not loaded");
    private int mRefreshGeneration;

    DesktopTaskSnapshotController(
            final DesktopShellActivity activity,
            final WorkspaceAppController workspace) {
        mActivity = activity;
        mWorkspace = workspace;
    }

    TaskRepository.Snapshot snapshot() {
        return mSnapshot;
    }

    void setSnapshot(final TaskRepository.Snapshot snapshot) {
        if (snapshot != null) {
            mSnapshot = snapshot;
        }
    }

    void sync(final TaskRepository.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        TaskRepository.TaskEntry activeTask = null;
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.active) {
                activeTask = task;
                break;
            }
        }
        final boolean desktopActive =
                activeTask != null
                        && mActivity.getPackageName().equals(
                                activeTask.packageName);
        final boolean taskbarVisible = DesktopTaskbarVisibilityPolicy.isVisible(
                mActivity.getCurrentDisplayId() == android.view.Display.DEFAULT_DISPLAY,
                activeTask != null,
                activeTask != null && activeTask.isFreeform(),
                desktopActive,
                mActivity.isTaskbarVisible());
        mSnapshot = snapshot;
        if (activeTask != null
                && isTaskbarTask(activeTask)) {
            DesktopPreferences.recordRecentPackage(
                    mActivity, activeTask.packageName);
        }
        mWorkspace.syncSnapshot(snapshot);
        mActivity.renderTaskbarPins(mActivity.getLauncherApps());
        mActivity.setTaskbarVisible(taskbarVisible);
        mActivity.setDesktopWindowFocusable(activeTask == null || desktopActive);
    }

    void refresh() {
        final int generation = ++mRefreshGeneration;
        final int displayId = mActivity.getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot ->
                mActivity.runOnUiThread(() -> {
                    if (generation != mRefreshGeneration
                            || mActivity.isActivityUnavailable()
                            || displayId != mActivity.getCurrentDisplayId()) {
                        return;
                    }
                    if (snapshot.available) {
                        sync(snapshot);
                    } else {
                        mSnapshot = snapshot;
                        mWorkspace.syncSnapshot(snapshot);
                        mActivity.renderTaskbarPins(
                                mActivity.getLauncherApps());
                    }
                    mActivity.updateDesktopControls();
                }));
    }

    TaskRepository.TaskEntry findFirstTask(final String packageName) {
        for (final TaskRepository.TaskEntry task : mSnapshot.tasks) {
            if (isTaskbarTask(task)
                    && packageName.equals(task.packageName)) {
                return task;
            }
        }
        return null;
    }

    List<TaskRepository.TaskEntry> findTasks(final String packageName) {
        final List<TaskRepository.TaskEntry> result = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : mSnapshot.tasks) {
            if (isTaskbarTask(task)
                    && packageName.equals(task.packageName)) {
                result.add(task);
            }
        }
        return result;
    }

    boolean isTaskbarTask(final TaskRepository.TaskEntry task) {
        return task != null
                && !task.home
                && task.packageName != null
                && !mActivity.getPackageName().equals(task.packageName);
    }

    void release() {
        mRefreshGeneration++;
    }
}
