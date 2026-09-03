package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
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

    DesktopTaskSnapshotController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    TaskRepository.Snapshot snapshot() {
        return mSnapshot;
    }

    TaskRepository.Snapshot setSnapshot(
            final TaskRepository.Snapshot snapshot) {
        mSnapshot = selectDesktopTaskSnapshot(snapshot);
        return mSnapshot;
    }

    void sync(final TaskRepository.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        final TaskRepository.Snapshot desktopSnapshot =
                selectDesktopTaskSnapshot(snapshot);
        if (!desktopSnapshot.available) {
            mSnapshot = desktopSnapshot;
            mActivity.renderTaskbarPins(mActivity.getLauncherApps());
            return;
        }
        TaskRepository.TaskEntry activeTask = null;
        for (final TaskRepository.TaskEntry task : desktopSnapshot.tasks) {
            if (task.active) {
                activeTask = task;
                break;
            }
        }
        // The taskbar plane remains available for edge reveal throughout an
        // active session. Policy visibility instead follows the physical
        // workspace: treating session foreground as HOME foreground would
        // keep the taskbar pinned over a selected fullscreen task.
        final boolean desktopHostActive =
                isDesktopHostForeground(desktopSnapshot.tasks);
        final boolean hasVisibleFreeformTask = hasVisibleFreeformTask(
                desktopSnapshot.tasks);
        final boolean hasVisibleFullscreenTask = hasVisibleFullscreenTask(
                desktopSnapshot.tasks);
        final boolean taskbarVisible = mSystemDialogHold.applySnapshot(
                DesktopTaskbarVisibilityPolicy.isVisible(
                        mActivity.getCurrentDisplayId()
                                == android.view.Display.DEFAULT_DISPLAY,
                        activeTask != null,
                        hasVisibleFreeformTask,
                        hasVisibleFullscreenTask,
                        desktopHostActive,
                        mActivity.isTaskbarVisible()));
        mSnapshot = desktopSnapshot;
        if (activeTask != null
                && isTaskbarTask(activeTask)) {
            DesktopPreferences.recordRecentApp(
                    mActivity,
                    BuiltInDesktopAppCatalog.appIdentityKey(activeTask));
        }
        mActivity.renderTaskbarPins(mActivity.getLauncherApps());
        mActivity.setTaskbarVisible(taskbarVisible);
        mActivity.setDesktopWindowFocusable(
                activeTask == null || desktopHostActive);
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

    static boolean hasVisibleFreeformTask(
            final List<TaskRepository.TaskEntry> tasks) {
        return hasVisibleFreeformTask(tasks, -1);
    }

    static boolean hasVisibleFreeformTask(
            final List<TaskRepository.TaskEntry> tasks,
            final int excludedTaskId) {
        if (tasks == null) {
            return false;
        }
        // Running tasks are top-first. Independent task-display areas may
        // report a freeform task as visible even while an opaque fullscreen
        // plane covers it, so only inspect applications above that plane.
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || task.taskId == excludedTaskId
                    || !task.visible
                    || DesktopTaskbarActivity.isTaskbarTask(task)
                    || TaskAreaBackstopActivity.isBackstopTask(task)) {
                continue;
            }
            if (DesktopTaskController.isDesktopHostTask(task)) {
                return false;
            }
            if (!DesktopManagedTaskPolicy
                    .isControllableApplicationTask(task)) {
                continue;
            }
            if (task.isFreeform()) {
                return true;
            }
            if (task.isFullscreen()) {
                return false;
            }
        }
        return false;
    }

    static boolean hasVisibleFullscreenTask(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return false;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || !task.visible
                    || DesktopTaskbarActivity.isTaskbarTask(task)
                    || TaskAreaBackstopActivity.isBackstopTask(task)) {
                continue;
            }
            if (DesktopTaskController.isDesktopHostTask(task)) {
                return false;
            }
            if (!DesktopManagedTaskPolicy
                    .isControllableApplicationTask(task)) {
                continue;
            }
            if (task.isFreeform()) {
                return false;
            }
            if (task.isFullscreen()) {
                return true;
            }
        }
        return false;
    }

    static boolean isDesktopHostForeground(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return false;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null && task.visible
                    && !DesktopTaskbarActivity.isTaskbarTask(task)
                    && !TaskAreaBackstopActivity.isBackstopTask(task)) {
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
