package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.util.List;

/** Restores a requested phone touchpad displaced by a window transition. */
final class PhoneTouchpadReconciler {
    private static final String TAG = "MagicDeskTasks";

    private boolean mRepairAwaitingVisibility;
    private int mLastRepairForegroundTaskId = Integer.MIN_VALUE;
    private int mLastRepairTouchpadTaskId = Integer.MIN_VALUE;

    void reset() {
        clearPendingRepair();
    }

    void reconcile(
            final int displayId,
            final List<TaskRepository.TaskEntry> phoneTasks) {
        DesktopSelfTestPhoneUiObserver.observePhoneTasks(phoneTasks);
        final RepairAction action = nextRepair(
                PhoneTouchpadController.shouldRemainVisible(displayId),
                phoneTasks);
        if (action == RepairAction.NONE) {
            return;
        }
        Log.i(TAG, "phone touchpad restore requested after task displacement"
                + " action=" + action.logName);
        if (!PhoneTouchpadController.restoreRequestedTask(displayId)) {
            Log.w(TAG,
                    "touchpad restore skipped; request is no longer active");
        }
    }

    RepairAction nextRepair(
            final boolean requested,
            final List<TaskRepository.TaskEntry> phoneTasks) {
        final PhoneTaskState state = inspectPhoneTasks(phoneTasks);
        // A visible phone task owns phone navigation. Keep the touchpad request
        // but repair only an exposed HOME/empty workspace, not another app.
        if (!requested || state.touchpadVisible || state.phoneTaskVisible) {
            clearPendingRepair();
            return RepairAction.NONE;
        }

        if (mRepairAwaitingVisibility
                && mLastRepairForegroundTaskId == state.foregroundTaskId
                && mLastRepairTouchpadTaskId == state.touchpadTaskId) {
            return RepairAction.NONE;
        }

        // Command acceptance is not restoration. Keep the repair pending until
        // the task observer confirms that the touchpad is visible again. An
        // unchanged sampled snapshot must not repeat the command.
        mRepairAwaitingVisibility = true;
        mLastRepairForegroundTaskId = state.foregroundTaskId;
        mLastRepairTouchpadTaskId = state.touchpadTaskId;
        return state.touchpadTaskId >= 0
                ? RepairAction.BRING_EXISTING
                : RepairAction.START_MISSING;
    }

    private void clearPendingRepair() {
        mRepairAwaitingVisibility = false;
        mLastRepairForegroundTaskId = Integer.MIN_VALUE;
        mLastRepairTouchpadTaskId = Integer.MIN_VALUE;
    }

    private static PhoneTaskState inspectPhoneTasks(
            final List<TaskRepository.TaskEntry> tasks) {
        int foregroundTaskId = -1;
        int touchpadTaskId = -1;
        boolean touchpadVisible = false;
        boolean phoneTaskVisible = false;
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task == null || task.displayId != android.view.Display.DEFAULT_DISPLAY) {
                    continue;
                }
                if (foregroundTaskId < 0 && task.visible) {
                    foregroundTaskId = task.taskId;
                }
                if (isTouchpadTask(task)) {
                    touchpadTaskId = task.taskId;
                    touchpadVisible |= task.visible;
                } else if (task.visible && !task.home) {
                    phoneTaskVisible = true;
                }
            }
        }
        return new PhoneTaskState(
                foregroundTaskId,
                touchpadTaskId,
                touchpadVisible,
                phoneTaskVisible);
    }

    private static boolean isTouchpadTask(
            final TaskRepository.TaskEntry task) {
        return isTouchpadComponent(task.componentName)
                || isTouchpadComponent(task.topActivityName);
    }

    private static boolean isTouchpadComponent(final String component) {
        final String packageName = BuildConfig.APPLICATION_ID;
        return (packageName + "/.MagicDeskTouchpadActivity").equals(component)
                || (packageName + "/" + packageName + ".MagicDeskTouchpadActivity")
                        .equals(component);
    }

    enum RepairAction {
        NONE("none"),
        BRING_EXISTING("bring-existing"),
        START_MISSING("start-missing");

        final String logName;

        RepairAction(final String logName) {
            this.logName = logName;
        }
    }

    private static final class PhoneTaskState {
        final int foregroundTaskId;
        final int touchpadTaskId;
        final boolean touchpadVisible;
        final boolean phoneTaskVisible;

        PhoneTaskState(
                final int foregroundTaskId,
                final int touchpadTaskId,
                final boolean touchpadVisible,
                final boolean phoneTaskVisible) {
            this.foregroundTaskId = foregroundTaskId;
            this.touchpadTaskId = touchpadTaskId;
            this.touchpadVisible = touchpadVisible;
            this.phoneTaskVisible = phoneTaskVisible;
        }
    }
}
