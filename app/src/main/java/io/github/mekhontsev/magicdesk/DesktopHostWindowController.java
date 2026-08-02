package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowMetrics;

/**
 * Keeps MagicDesk's desktop host in a translucent fullscreen task.
 *
 * <p>The task remains visually opaque, but WindowManager's force-translucent
 * state prevents it from pausing covered applications. The fullscreen
 * transition excludes and refreshes Nubia's stale caption surface so it does
 * not occupy the top of the display.</p>
 */
final class DesktopHostWindowController {
    private static final String DIAGNOSTIC_CODE = "TASKS-001";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 500L;

    private final DesktopShellActivity mActivity;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRetry = this::ensureConfigured;
    private int mGeneration;
    private int mAttempts;
    private boolean mPending;
    private boolean mReady;

    DesktopHostWindowController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void ensureConfigured() {
        if (mActivity.isActivityUnavailable()) {
            return;
        }
        if (mPending || mAttempts >= MAX_ATTEMPTS
                || !ShellAccess.isReady()) {
            return;
        }

        mReady = false;
        mPending = true;
        mAttempts++;
        final int generation = ++mGeneration;
        final int displayId = mActivity.getCurrentDisplayId();
        final int taskId = mActivity.getTaskId();
        final Rect hostBounds = readHostBounds();
        TaskRepository.load(displayId, snapshot ->
                mActivity.runOnUiThread(() -> {
                    if (!isCurrent(generation)) {
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
                    if (task.isFullscreen()
                            && hostBounds.equals(task.bounds)) {
                        mReady = true;
                        resetAttempts();
                        return;
                    }
                    TaskRepository.configureDesktopHost(task, result ->
                            mActivity.runOnUiThread(() -> {
                                if (!isCurrent(generation)) {
                                    return;
                                }
                                mPending = false;
                                if (!result.success) {
                                    retryOrRecord(
                                            generation,
                                            "Could not configure desktop task " + taskId
                                                    + " on display "
                                                    + displayId + ": "
                                                    + result.message);
                                    return;
                                }
                                mActivity.refreshTaskSnapshot();
                                mMainHandler.removeCallbacks(mRetry);
                                mMainHandler.postDelayed(
                                        mRetry, RETRY_DELAY_MS);
                            }));
                }));
    }

    void onMultiWindowModeChanged(final boolean inMultiWindowMode) {
        mReady = false;
        mGeneration++;
        resetAttempts();
        ensureConfigured();
    }

    boolean isReady() {
        return mReady;
    }

    void release() {
        mGeneration++;
        mReady = false;
        resetAttempts();
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
        if (mAttempts < MAX_ATTEMPTS) {
            mMainHandler.removeCallbacks(mRetry);
            mMainHandler.postDelayed(mRetry, RETRY_DELAY_MS);
            return;
        }
        recordFailure(detail);
    }

    private void resetAttempts() {
        mMainHandler.removeCallbacks(mRetry);
        mPending = false;
        mAttempts = 0;
    }

    private Rect readHostBounds() {
        final WindowMetrics metrics = mActivity.getWindowManager()
                .getMaximumWindowMetrics();
        final Rect bounds = metrics.getBounds();
        return new Rect(0, 0,
                Math.max(1, bounds.width()),
                Math.max(1, bounds.height()));
    }

    private void recordFailure(final String detail) {
        CompatibilityDiagnostics.record(
                DIAGNOSTIC_CODE,
                "MagicDesk could not configure its desktop host",
                detail);
    }
}
