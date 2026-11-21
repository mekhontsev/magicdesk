package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.List;

/** Owns the current task snapshot and serialized asynchronous refreshes. */
final class DesktopTaskSnapshotController {
    private final DesktopShellActivity mActivity;
    private final DesktopTaskbarDialogHold mSystemDialogHold =
            new DesktopTaskbarDialogHold();

    private TaskRepository.Snapshot mSnapshot = new TaskRepository.Snapshot(
            Collections.<TaskRepository.TaskEntry>emptyList(),
            false,
            "not loaded");
    private int mRefreshGeneration;
    private int mRecentTaskId = -1;

    DesktopTaskSnapshotController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    TaskRepository.Snapshot snapshot() {
        return mSnapshot;
    }

    TaskRepository.Snapshot setSnapshot(
            final TaskRepository.Snapshot snapshot) {
        mSnapshot = selectDesktopTaskSnapshot(snapshot);
        mActivity.publishShellTasks(mSnapshot);
        return mSnapshot;
    }

    void sync(final TaskRepository.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        final TaskRepository.Snapshot desktopSnapshot =
                selectDesktopTaskSnapshot(snapshot);
        mActivity.publishShellTasks(desktopSnapshot);
        if (!desktopSnapshot.available) {
            mSnapshot = desktopSnapshot;
            mActivity.renderTaskbarPins(mActivity.getLauncherApps());
            return;
        }
        final TaskRepository.TaskEntry activeTask =
                findActiveTask(desktopSnapshot.tasks);
        final TaskRepository.TaskEntry displayActiveTask =
                findActiveTask(snapshot.tasks);
        final boolean taskbarVisible = mSystemDialogHold.applySnapshot(
                DesktopTaskbarVisibilityPolicy.isVisible(
                        mActivity.getCurrentDisplayId()
                                == android.view.Display.DEFAULT_DISPLAY,
                        DesktopWorkspaceScene.resolve(snapshot.tasks),
                        mActivity.isTaskbarVisible()));
        mSnapshot = desktopSnapshot;
        if (activeTask != null
                && isTaskbarTask(activeTask) && activeTask.taskId != mRecentTaskId) {
            RecentApplications.recordTask(mActivity, activeTask, mActivity.getLauncherApps());
        }
        mRecentTaskId = activeTask == null ? -1 : activeTask.taskId;
        mActivity.renderTaskbarPins(mActivity.getLauncherApps());
        mActivity.setTaskbarVisible(taskbarVisible);
        // Command preparation survives intermediate observations until its
        // input commit. Outside a command, follow focus rather than Z-order.
        final int inputTarget = MagicDeskRuntime.taskInputFocusTarget(
                mActivity.getCurrentDisplayId(),
                displayActiveTask == null ? -1 : displayActiveTask.taskId);
        mActivity.setDesktopWindowFocusable(
                inputTarget < 0 || inputTarget == mActivity.getTaskId());
    }

    boolean setSystemDialogVisible(final boolean visible) {
        if (!mSystemDialogHold.setDialogVisible(
                visible, mActivity.isTaskbarVisible())) {
            return false;
        }
        mActivity.setTaskbarVisible(mSystemDialogHold.currentVisibility(
                mActivity.isTaskbarVisible()));
        if (!visible) {
            refresh();
        }
        return true;
    }

    static TaskRepository.TaskEntry findActiveTask(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null && task.active) {
                return task;
            }
        }
        return null;
    }

    void refresh() {
        final int generation = ++mRefreshGeneration;
        final int displayId = mActivity.getCurrentDisplayId();
        if (displayId >= 0 && DesktopRuntimeBridge.getSessionSnapshot(displayId)
                .activeWorkspaceDisplayId() == displayId) {
            applyRefreshSnapshot(generation, displayId, null);
            return;
        }
        TaskRepository.load(displayId, snapshot ->
                applyRefreshSnapshot(generation, displayId, snapshot));
    }

    private void applyRefreshSnapshot(
            final int generation,
            final int displayId,
            final TaskRepository.Snapshot snapshot) {
        mActivity.runOnUiThread(() -> {
            if (generation != mRefreshGeneration
                    || mActivity.isActivityUnavailable()
                    || displayId != mActivity.getCurrentDisplayId()) {
                return;
            }
            // Active desktop chrome follows the controller's publication, not
            // an independent query that can observe an in-flight handoff.
            final TaskRepository.Snapshot current = displayId >= 0
                    && DesktopRuntimeBridge.getSessionSnapshot(displayId)
                            .activeWorkspaceDisplayId() == displayId
                    ? MagicDeskRuntime.observedTaskSnapshot(displayId) : snapshot;
            if (current != null && current.available) {
                sync(current);
            } else {
                mSnapshot = current == null
                        ? new TaskRepository.Snapshot(Collections.emptyList(),
                                false, "desktop task observation unavailable")
                        : current;
                mActivity.renderTaskbarPins(mActivity.getLauncherApps());
            }
            mActivity.updateDesktopControls();
        });
    }

    TaskRepository.TaskEntry findFirstTask(final AppItem app) {
        if (app == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : mSnapshot.tasks) {
            if (isTaskbarTask(task) && app.matchesTask(task)) {
                return task;
            }
        }
        return null;
    }

    boolean isTaskbarTask(final TaskRepository.TaskEntry task) {
        return DesktopManagedTaskPolicy.isManagedApplicationTask(task);
    }

    void release() {
        mRefreshGeneration++;
    }

    private TaskRepository.Snapshot selectDesktopTaskSnapshot(
            final TaskRepository.Snapshot snapshot) {
        if (snapshot == null) {
            return new TaskRepository.Snapshot(
                    Collections.emptyList(),
                    false,
                    "task snapshot unavailable");
        }
        return MagicDeskRuntime.selectDesktopTaskSnapshot(
                mActivity.getCurrentDisplayId(), snapshot);
    }
}
