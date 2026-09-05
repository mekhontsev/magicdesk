package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.util.List;

/** Restores a requested phone touchpad displaced by a window transition. */
final class PhoneTouchpadReconciler {
    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_TOUCHPAD_ACTIVITY =
            "io.github.mekhontsev.magicdesk.MagicDeskTouchpadActivity";

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
        // The control panel is an intentional phone-side destination, not a
        // displaced touchpad. Preserve the request without covering the panel.
        if (!requested || state.touchpadVisible || state.controlPanelVisible) {
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
        boolean controlPanelVisible = false;
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task == null) {
                    continue;
                }
                if (foregroundTaskId < 0 && task.visible) {
                    foregroundTaskId = task.taskId;
                }
                if (isTouchpadTask(task)) {
                    touchpadTaskId = task.taskId;
                    touchpadVisible |= task.visible;
                }
                if (task.visible
                        && (isControlPanelComponent(task.componentName)
                                || isControlPanelComponent(task.topActivityName))) {
                    controlPanelVisible = true;
                }
            }
        }
        return new PhoneTaskState(
                foregroundTaskId,
                touchpadTaskId,
                touchpadVisible,
                controlPanelVisible);
    }

    private static boolean isControlPanelComponent(final String component) {
        final String packageName = BuildConfig.APPLICATION_ID;
        return (packageName + "/.ControlActivity").equals(component)
                || (packageName + "/" + packageName + ".ControlActivity")
                        .equals(component);
    }

    private static boolean isTouchpadTask(
            final TaskRepository.TaskEntry task) {
        return isTouchpadComponent(task.componentName)
                || isTouchpadComponent(task.topActivityName);
    }

    private static boolean isTouchpadComponent(final String component) {
        return component != null
                && component.endsWith(MAGICDESK_TOUCHPAD_ACTIVITY);
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
        final boolean controlPanelVisible;

        PhoneTaskState(
                final int foregroundTaskId,
                final int touchpadTaskId,
                final boolean touchpadVisible,
                final boolean controlPanelVisible) {
            this.foregroundTaskId = foregroundTaskId;
            this.touchpadTaskId = touchpadTaskId;
            this.touchpadVisible = touchpadVisible;
            this.controlPanelVisible = controlPanelVisible;
        }
    }
}
