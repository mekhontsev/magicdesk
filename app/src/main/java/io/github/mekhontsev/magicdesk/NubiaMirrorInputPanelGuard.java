package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.util.Log;

/** Prevents Nubia's phone panel from replacing MagicDesk's active touchpad. */
final class NubiaMirrorInputPanelGuard implements AutoCloseable {
    interface InputOwner {
        boolean isActive();
        void reclaimInput();
    }

    private static final String TAG = "MagicDeskTouchpad";
    private static final ComponentName PANEL_ACTIVITY = new ComponentName(
            "cn.nubia.keymapcenter",
            "cn.nubia.keymapcenter.mirror.MirrorInputActivity");

    private final InputOwner mInputOwner;
    private final Object mTaskService;

    private boolean mEnabled;
    private boolean mClosed;
    private int mRemovingTaskId = -1;

    NubiaMirrorInputPanelGuard(
            final Object taskService,
            final InputOwner inputOwner) {
        mTaskService = taskService;
        mInputOwner = inputOwner;
    }

    synchronized void configure(final int displayId) {
        mEnabled = displayId > 0;
        if (!mEnabled) {
            mRemovingTaskId = -1;
        }
    }

    void onTaskAppeared(
            final int taskId,
            final ComponentName componentName) {
        if (!PANEL_ACTIVITY.equals(componentName)) {
            return;
        }
        synchronized (this) {
            if (!mEnabled
                    || mClosed
                    || mRemovingTaskId == taskId
                    || !mInputOwner.isActive()) {
                return;
            }
            mRemovingTaskId = taskId;
        }
        try {
            if (!TaskControlCommand.removeTask(mTaskService, taskId)) {
                throw new IllegalStateException(
                        "removeTask returned false for task " + taskId);
            }
            Log.i(TAG, "removed automatic Nubia input panel task=" + taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            synchronized (this) {
                if (mRemovingTaskId == taskId) {
                    mRemovingTaskId = -1;
                }
            }
            Log.w(TAG,
                    "could not remove Nubia input panel",
                    error);
        }
    }

    void onTaskRemoved(final int taskId) {
        synchronized (this) {
            if (mRemovingTaskId != taskId) {
                return;
            }
            mRemovingTaskId = -1;
        }
        mInputOwner.reclaimInput();
    }

    @Override
    public synchronized void close() {
        mClosed = true;
        mEnabled = false;
        mRemovingTaskId = -1;
    }
}
