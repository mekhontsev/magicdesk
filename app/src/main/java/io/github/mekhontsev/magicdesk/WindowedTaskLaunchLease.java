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
                ConsoleModeSwitcher.isTouchpadVisible());
    }

    void protectStartupTask(final int taskId) {
        if (taskId < 0 || mClosed || mStartupTaskId == taskId) {
            return;
        }
        if (mStartupTaskId >= 0) {
            MagicDeskRuntime.finishExplicitWindowedLaunch(mStartupTaskId);
        }
        mStartupTaskId = taskId;
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
        if (mStartupTaskId >= 0) {
            MagicDeskRuntime.finishExplicitWindowedLaunch(mStartupTaskId);
        }
        if (mRestoreTouchpad) {
            MagicDeskRuntime.finishTouchpadPreservation();
            ConsoleModeSwitcher.restoreTouchpadIfMissing();
        }
    }
}
