package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;

/** Owns the current task snapshot and serialized asynchronous refreshes. */
final class DesktopTaskSnapshotController {
    private final DesktopShellActivity mActivity;

    private TaskRepository.Snapshot mSnapshot = new TaskRepository.Snapshot(
            java.util.Collections.<TaskRepository.TaskEntry>emptyList(),
            false,
            "not loaded");
    private int mRefreshGeneration;

    DesktopTaskSnapshotController(final DesktopShellActivity activity) {
        mActivity = activity;
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
                isDesktopHostForeground(snapshot.tasks);
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
        mActivity.renderTaskbarPins(mActivity.getLauncherApps());
        mActivity.setTaskbarVisible(taskbarVisible);
        mActivity.setDesktopWindowFocusable(activeTask == null || desktopActive);
    }

    static boolean isDesktopHostForeground(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return false;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null && task.visible) {
                return DesktopTaskController.isDesktopHostTask(task);
            }
        }
        return false;
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

    TaskRepository.TaskEntry findFirstTask(final AppLaunchTarget target) {
        if (target == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : mSnapshot.tasks) {
            if (isTaskbarTask(task) && target.matchesTask(task)) {
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
        return DesktopManagedTaskPolicy.isManagedApplicationTask(task);
    }

    void release() {
        mRefreshGeneration++;
    }
}
