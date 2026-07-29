package io.github.mekhontsev.magicdesk;

import android.os.Handler;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DesktopPhoneUiReconciler {
    interface RuntimeState {
        boolean isRunning();
    }

    private static final String TAG = "MagicDeskTasks";
    private static final String TOUCHPAD_ACTIVITY =
            "cn.nubia.keymapcenter.mirror.MirrorInputActivity";
    private static final String SECONDARY_HOME_ACTIVITY =
            "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher";

    private final Handler mHandler;
    private final RuntimeState mRuntimeState;
    private final Set<Integer> mLastVisibleAppTaskIds = new HashSet<>();

    private Boolean mLastTouchpadVisible;
    private boolean mTouchpadRestorePending;
    private boolean mTouchpadRestoreAttemptInProgress;

    DesktopPhoneUiReconciler(
            final Handler handler,
            final RuntimeState runtimeState) {
        mHandler = handler;
        mRuntimeState = runtimeState;
    }

    void reset() {
        mLastVisibleAppTaskIds.clear();
        mLastTouchpadVisible = null;
        mTouchpadRestorePending = false;
        mTouchpadRestoreAttemptInProgress = false;
    }

    void reconcile(
            final List<TaskRepository.TaskEntry> phoneTasks,
            final Set<Integer> visibleAppTaskIds,
            final boolean focusingExternalTask) {
        boolean touchpadVisible = false;
        boolean secondaryHomeVisible = false;
        for (final TaskRepository.TaskEntry task : phoneTasks) {
            if (task == null || !task.visible || task.componentName == null) {
                continue;
            }
            if (task.componentName.endsWith(TOUCHPAD_ACTIVITY)) {
                touchpadVisible = true;
            } else if (task.componentName.endsWith(SECONDARY_HOME_ACTIVITY)) {
                secondaryHomeVisible = true;
            }
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
                Log.i(TAG, "Nubia touchpad displaced by external task minimize");
                mTouchpadRestorePending = true;
            } else {
                Log.i(TAG, "restore phone Home displaced by external task minimize");
                ConsoleModeSwitcher.restorePrimaryPhoneHome();
            }
        }
        attemptPendingTouchpadRestore();

        mLastVisibleAppTaskIds.clear();
        mLastVisibleAppTaskIds.addAll(visibleAppTaskIds);
        mLastTouchpadVisible = Boolean.valueOf(touchpadVisible);
    }

    private void attemptPendingTouchpadRestore() {
        if (!mTouchpadRestorePending || mTouchpadRestoreAttemptInProgress) {
            return;
        }
        mTouchpadRestoreAttemptInProgress = true;
        ConsoleModeSwitcher.restoreTouchpadIfMissing((touchpadMissing, restored) ->
                mHandler.post(() -> {
                    mTouchpadRestoreAttemptInProgress = false;
                    if (!mRuntimeState.isRunning()) {
                        mTouchpadRestorePending = false;
                        return;
                    }
                    if (!touchpadMissing) {
                        Log.d(TAG, "touchpad transition is still in progress");
                        return;
                    }
                    mTouchpadRestorePending = !restored;
                    if (!restored) {
                        Log.w(TAG,
                                "touchpad restore failed; waiting for another task event");
                    }
                }));
    }
}
