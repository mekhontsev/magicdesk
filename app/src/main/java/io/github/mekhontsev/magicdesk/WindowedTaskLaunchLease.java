package io.github.mekhontsev.magicdesk;

/** Owns transient input and windowing state for one window launch operation. */
final class WindowedTaskLaunchLease implements AutoCloseable {
    private final boolean mRestoreTouchpad;
    private int mStartupTaskId = -1;
    private boolean mClosed;

    private WindowedTaskLaunchLease(final boolean restoreTouchpad) {
        mRestoreTouchpad = restoreTouchpad;
        if (restoreTouchpad) {
            MagicDeskRuntime.expectTouchpadDisplacement();
        }
    }

    static WindowedTaskLaunchLease acquire() {
        return new WindowedTaskLaunchLease(
                DesktopOperations.isTouchpadVisible());
    }

    void protectStartupTask(final int taskId) {
        if (taskId < 0 || mClosed || mStartupTaskId == taskId) {
            return;
        }
        mStartupTaskId = taskId;
        // Once a task id exists, the task runtime owns startup protection.
        // Closing this operation lease must not race the app's first frame.
        MagicDeskRuntime.beginExplicitWindowedLaunch(taskId);
    }

    void noteFreeformTask(final int taskId) {
        if (taskId >= 0 && !mClosed && taskId != mStartupTaskId) {
            MagicDeskRuntime.noteManualFreeformTransition(taskId);
        }
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        if (mRestoreTouchpad) {
            MagicDeskRuntime.finishTouchpadPreservation();
            DesktopOperations.restoreTouchpadIfMissing();
        }
    }
}
