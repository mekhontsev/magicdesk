package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.util.List;

/** Restores a requested phone touchpad displaced by a window transition. */
final class PhoneTouchpadReconciler {
    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_TOUCHPAD_ACTIVITY =
            "io.github.mekhontsev.magicdesk.MagicDeskTouchpadActivity";

    private Boolean mLastVisible;
    private volatile boolean mPreservationArmed;
    private boolean mRestorePending;

    void reset() {
        mLastVisible = null;
        mPreservationArmed = false;
        mRestorePending = false;
    }

    void expectDisplacement() {
        mPreservationArmed = true;
    }

    void finishPreservation() {
        mPreservationArmed = false;
    }

    void reconcile(
            final int displayId,
            final List<TaskRepository.TaskEntry> phoneTasks) {
        DesktopSelfTestPhoneUiObserver.observePhoneTasks(phoneTasks);
        final boolean visible = isTouchpadVisible(phoneTasks);
        if (!visible
                && (mPreservationArmed
                        || (Boolean.TRUE.equals(mLastVisible)
                                && PhoneTouchpadController
                                        .shouldRemainVisible(displayId)))) {
            mPreservationArmed = false;
            mRestorePending = true;
            Log.i(TAG, "phone touchpad displaced by window transition");
        }
        if (mRestorePending) {
            mRestorePending = false;
            if (!PhoneTouchpadController.restoreObservedMissing(displayId)) {
                Log.w(TAG,
                        "touchpad restore skipped; request is no longer active");
            }
        }
        mLastVisible = Boolean.valueOf(visible);
    }

    private static boolean isTouchpadVisible(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return false;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null
                    && task.visible
                    && task.componentName != null
                    && task.componentName.endsWith(
                            MAGICDESK_TOUCHPAD_ACTIVITY)) {
                return true;
            }
        }
        return false;
    }
}
