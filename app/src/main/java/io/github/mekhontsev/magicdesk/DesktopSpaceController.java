package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;

final class DesktopSpaceController {
    private final DesktopShellActivity mActivity;
    private boolean mSwitchInProgress;

    DesktopSpaceController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    int activeSpace() {
        return DesktopSpaceStateStore.activeSpace(
                mActivity.getCurrentDisplayId());
    }

    void sync(final TaskRepository.Snapshot snapshot) {
        if (mSwitchInProgress || snapshot == null
                || !snapshot.rootAvailable) {
            return;
        }
        DesktopSpaceStateStore.sync(
                mActivity.getCurrentDisplayId(),
                snapshot.tasks,
                mActivity.getPackageName());
        updateUi();
    }

    boolean isInActiveSpace(final TaskRepository.TaskEntry task) {
        return DesktopSpaceStateStore.isInActiveSpace(
                mActivity.getCurrentDisplayId(),
                task,
                mActivity.getPackageName());
    }

    void switchTo(final int targetSpace) {
        final int displayId = mActivity.getCurrentDisplayId();
        final int previousSpace =
                DesktopSpaceStateStore.activeSpace(displayId);
        if (mSwitchInProgress || targetSpace == previousSpace
                || targetSpace < 0
                || targetSpace >= DesktopSpaceStateStore.SPACE_COUNT
                || !RuntimeAccess.has(
                        RuntimeAccess.Capability.TASK_CONTROL)) {
            return;
        }
        final TaskRepository.Snapshot snapshot =
                mActivity.getTaskSnapshot();
        if (snapshot == null || !snapshot.rootAvailable) {
            return;
        }
        DesktopSpaceStateStore.sync(
                displayId, snapshot.tasks, mActivity.getPackageName());
        final List<TaskRepository.TaskEntry> hideTasks =
                DesktopSpaceStateStore.tasksInSpace(
                        displayId, previousSpace, snapshot.tasks,
                        mActivity.getPackageName());
        final List<TaskRepository.TaskEntry> restoreTasks =
                DesktopSpaceStateStore.tasksInSpace(
                        displayId, targetSpace, snapshot.tasks,
                        mActivity.getPackageName());
        final List<Integer> restoreTaskIds = taskIds(restoreTasks);
        if (restoreTaskIds.isEmpty()) {
            // Keep the Console display alive by exposing its HOME task in the
            // same organizer transaction that hides the previous workspace.
            // Nubia treats a later AppTask.moveToFront() as leaving app mirror.
            restoreTaskIds.add(Integer.valueOf(mActivity.getTaskId()));
        }

        mSwitchInProgress = true;
        DesktopSpaceStateStore.setActiveSpace(displayId, targetSpace);
        updateUi();
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_desktop_space_switching,
                Integer.valueOf(targetSpace + 1)));
        TaskRepository.switchDesktopSpace(
                displayId,
                taskIds(hideTasks),
                restoreTaskIds,
                result -> mActivity.runOnUiThread(() -> {
                    if (!result.success) {
                        mSwitchInProgress = false;
                        DesktopSpaceStateStore.setActiveSpace(
                                displayId, previousSpace);
                        mActivity.setErrorStatus(
                                "DESKTOP-SPACE-001",
                                mActivity.getString(
                                        R.string.status_switch_failed,
                                        result.message));
                        updateUi();
                        return;
                    }
                    if (restoreTasks.isEmpty()) {
                        finishSwitch(targetSpace);
                        return;
                    }
                    TaskRepository.bringStackToFront(
                            restoreTasks,
                            restoreTasks.get(0),
                            focusResult -> mActivity.runOnUiThread(() -> {
                                if (!focusResult.success) {
                                    mSwitchInProgress = false;
                                    mActivity.setErrorStatus(
                                            "DESKTOP-SPACE-002",
                                            mActivity.getString(
                                                    R.string.status_switch_failed,
                                                    focusResult.message));
                                    mActivity.refreshTaskSnapshot();
                                    return;
                                }
                                finishSwitch(targetSpace);
                            }));
                }));
    }

    void next() {
        switchTo((activeSpace() + 1)
                % DesktopSpaceStateStore.SPACE_COUNT);
    }

    void previous() {
        switchTo((activeSpace()
                + DesktopSpaceStateStore.SPACE_COUNT - 1)
                % DesktopSpaceStateStore.SPACE_COUNT);
    }

    private void updateUi() {
        mActivity.taskbar().updateDesktopSpace(activeSpace());
        mActivity.updateDesktopSpaceControls(activeSpace());
    }

    private void finishSwitch(final int targetSpace) {
        mSwitchInProgress = false;
        mActivity.setStatus(mActivity.getString(
                R.string.status_desktop_space_active,
                Integer.valueOf(targetSpace + 1)));
        mActivity.refreshTaskSnapshot();
    }

    private static List<Integer> taskIds(
            final List<TaskRepository.TaskEntry> tasks) {
        final List<Integer> ids = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : tasks) {
            ids.add(Integer.valueOf(task.taskId));
        }
        return ids;
    }
}
