package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DesktopPhoneUiReconciler {
    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_TOUCHPAD_ACTIVITY =
            "io.github.mekhontsev.magicdesk.MagicDeskTouchpadActivity";
    private static final String NUBIA_TOUCHPAD_ACTIVITY =
            "cn.nubia.keymapcenter.mirror.MirrorInputActivity";
    private final PhoneHomeComponents mHomeComponents;

    private final Set<Integer> mLastVisibleAppTaskIds = new HashSet<>();

    private Boolean mLastTouchpadVisible;
    private volatile boolean mTouchpadPreservationArmed;
    private boolean mTouchpadRestorePending;
    private boolean mAwaitingNubiaPanelRemoval;

    DesktopPhoneUiReconciler(final Context context) {
        mHomeComponents = PhoneHomeComponents.resolve(context);
    }

    void reset() {
        mLastVisibleAppTaskIds.clear();
        mLastTouchpadVisible = null;
        mTouchpadPreservationArmed = false;
        mTouchpadRestorePending = false;
        mAwaitingNubiaPanelRemoval = false;
    }

    void expectTouchpadDisplacement() {
        mTouchpadPreservationArmed = true;
    }

    void finishTouchpadPreservation() {
        mTouchpadPreservationArmed = false;
    }

    void reconcile(
            final int displayId,
            final List<TaskRepository.TaskEntry> phoneTasks,
            final Set<Integer> visibleAppTaskIds,
            final boolean focusingExternalTask) {
        boolean touchpadVisible = false;
        boolean nubiaPanelVisible = false;
        boolean secondaryHomeVisible = false;
        for (final TaskRepository.TaskEntry task : phoneTasks) {
            if (task == null || !task.visible || task.componentName == null) {
                continue;
            }
            if (task.componentName.endsWith(
                    MAGICDESK_TOUCHPAD_ACTIVITY)) {
                touchpadVisible = true;
            } else if (hasActivity(task, NUBIA_TOUCHPAD_ACTIVITY)) {
                nubiaPanelVisible = true;
            } else if (mHomeComponents.isSecondaryTask(task)) {
                secondaryHomeVisible = true;
            }
        }

        if (nubiaPanelVisible
                && PhoneTouchpadController.shouldRemainVisible(displayId)) {
            mAwaitingNubiaPanelRemoval = true;
            mTouchpadRestorePending = true;
        } else if (!nubiaPanelVisible && mAwaitingNubiaPanelRemoval) {
            mAwaitingNubiaPanelRemoval = false;
            attemptPendingTouchpadRestore(displayId);
        }

        if (!touchpadVisible
                && !nubiaPanelVisible
                && (mTouchpadPreservationArmed
                        || (Boolean.TRUE.equals(mLastTouchpadVisible)
                                && PhoneTouchpadController
                                        .shouldRemainVisible(displayId)))) {
            mTouchpadPreservationArmed = false;
            mTouchpadRestorePending = true;
            Log.i(TAG,
                    "phone touchpad displaced by desktop window transition");
        }

        boolean externalTaskMinimized = false;
        for (final Integer taskId : mLastVisibleAppTaskIds) {
            if (!visibleAppTaskIds.contains(taskId)) {
                externalTaskMinimized = true;
                break;
            }
        }
        if (!focusingExternalTask && externalTaskMinimized
                && secondaryHomeVisible && !touchpadVisible
                && mLastTouchpadVisible != null) {
            if (mLastTouchpadVisible.booleanValue()) {
                Log.i(TAG, "phone touchpad displaced by external task minimize");
                mTouchpadRestorePending = true;
            } else {
                Log.i(TAG, "restore phone Home displaced by external task minimize");
                ConsoleModeSwitcher.restorePrimaryPhoneHome();
            }
        }
        attemptPendingTouchpadRestore(displayId);

        mLastVisibleAppTaskIds.clear();
        mLastVisibleAppTaskIds.addAll(visibleAppTaskIds);
        mLastTouchpadVisible = Boolean.valueOf(touchpadVisible);
    }

    private void attemptPendingTouchpadRestore(final int displayId) {
        if (!mTouchpadRestorePending
                || mAwaitingNubiaPanelRemoval) {
            return;
        }
        mTouchpadRestorePending = false;
        if (!PhoneTouchpadController.restoreObservedMissing(displayId)) {
            Log.w(TAG, "touchpad restore skipped; request is no longer active");
        }
    }

    private static boolean hasActivity(
            final TaskRepository.TaskEntry task,
            final String activityClassName) {
        return task.componentName.endsWith(activityClassName)
                || (task.topActivityName != null
                        && task.topActivityName.endsWith(activityClassName));
    }
}
