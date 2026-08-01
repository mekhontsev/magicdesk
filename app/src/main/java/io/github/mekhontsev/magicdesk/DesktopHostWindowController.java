package io.github.mekhontsev.magicdesk;

import android.os.Handler;
import android.os.Looper;

/**
 * Keeps MagicDesk's desktop host fullscreen when Android desktop mode tries to
 * inherit a freeform windowing mode from the task that launched it.
 *
 * <p>The full transition deliberately recreates the client. Nubia otherwise
 * leaves the initial freeform caption inset on the fullscreen DecorView.</p>
 */
final class DesktopHostWindowController {
    private static final String DIAGNOSTIC_CODE = "TASKS-001";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 500L;

    private final DesktopShellActivity mActivity;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRetry = this::ensureFullscreen;
    private int mGeneration;
    private int mAttempts;
    private boolean mPending;

    DesktopHostWindowController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void ensureFullscreen() {
        if (mActivity.isActivityUnavailable()) {
            return;
        }
        if (!mActivity.isInMultiWindowMode()) {
            reset();
            return;
        }
        if (mPending || mAttempts >= MAX_ATTEMPTS
                || !ShellAccess.isReady()) {
            return;
        }

        mPending = true;
        mAttempts++;
        final int generation = ++mGeneration;
        final int displayId = mActivity.getCurrentDisplayId();
        final int taskId = mActivity.getTaskId();
        TaskRepository.load(displayId, snapshot ->
                mActivity.runOnUiThread(() -> {
                    if (!isCurrent(generation)
                            || !mActivity.isInMultiWindowMode()) {
                        finishIfCurrent(generation);
                        return;
                    }
                    final TaskRepository.TaskEntry task =
                            DesktopShellActivity.findTask(snapshot, taskId);
                    if (!snapshot.available || task == null) {
                        finishIfCurrent(generation);
                        retryOrRecord(
                                generation,
                                "Could not inspect desktop task " + taskId
                                        + " on display " + displayId
                                        + ": " + snapshot.error);
                        return;
                    }
                    TaskRepository.setFullscreen(task, false, result ->
                            mActivity.runOnUiThread(() -> {
                                if (!isCurrent(generation)) {
                                    return;
                                }
                                mPending = false;
                                if (!result.success) {
                                    retryOrRecord(
                                            generation,
                                            "Could not make desktop task " + taskId
                                                    + " fullscreen on display "
                                                    + displayId + ": "
                                                    + result.message);
                                    return;
                                }
                                mActivity.refreshTaskSnapshot();
                            }));
                }));
    }

    void onMultiWindowModeChanged(final boolean inMultiWindowMode) {
        if (!inMultiWindowMode) {
            mGeneration++;
            reset();
            return;
        }
        ensureFullscreen();
    }

    void release() {
        mGeneration++;
        reset();
    }

    private boolean isCurrent(final int generation) {
        return generation == mGeneration && !mActivity.isActivityUnavailable();
    }

    private void finishIfCurrent(final int generation) {
        if (generation == mGeneration) {
            mPending = false;
        }
    }

    private void retryOrRecord(
            final int generation,
            final String detail) {
        if (!isCurrent(generation)) {
            return;
        }
        if (mAttempts < MAX_ATTEMPTS
                && mActivity.isInMultiWindowMode()) {
            mMainHandler.removeCallbacks(mRetry);
            mMainHandler.postDelayed(mRetry, RETRY_DELAY_MS);
            return;
        }
        recordFailure(detail);
    }

    private void reset() {
        mMainHandler.removeCallbacks(mRetry);
        mPending = false;
        mAttempts = 0;
    }

    private void recordFailure(final String detail) {
        CompatibilityDiagnostics.record(
                DIAGNOSTIC_CODE,
                "MagicDesk could not make its desktop host fullscreen",
                detail);
    }
}
