package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

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

    private final Host mHost;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRetry = this::ensureConfigured;
    private int mGeneration;
    private int mAttempts;
    private boolean mPending;
    private boolean mReady;
    private boolean mConfigurationApplied;

    DesktopHostWindowController(final Host host) {
        mHost = host;
    }

    void ensureConfigured() {
        if (mHost.isActivityUnavailable()) {
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
        final int displayId = mHost.getCurrentDisplayId();
        final int taskId = mHost.getTaskId();
        final Rect hostBounds = readHostBounds();
        TaskRepository.load(displayId, snapshot ->
                mHost.runOnUiThread(() -> {
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
                    if (mConfigurationApplied
                            && task.isFullscreen()
                            && hostBounds.equals(task.bounds)) {
                        mReady = true;
                        resetAttempts();
                        return;
                    }
                    TaskRepository.configureDesktopHost(task, result ->
                            mHost.runOnUiThread(() -> {
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
                                mConfigurationApplied = true;
                                mHost.refreshTaskSnapshot();
                                mMainHandler.removeCallbacks(mRetry);
                                mMainHandler.postDelayed(
                                        mRetry, RETRY_DELAY_MS);
                            }));
                }));
    }

    void onMultiWindowModeChanged(final boolean inMultiWindowMode) {
        if (mPending) {
            return;
        }
        mReady = false;
        mConfigurationApplied = false;
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
        mConfigurationApplied = false;
        resetAttempts();
    }

    private boolean isCurrent(final int generation) {
        return generation == mGeneration && !mHost.isActivityUnavailable();
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
        final Rect bounds = mHost.getMaximumWindowBounds();
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

    interface Host {
        boolean isActivityUnavailable();

        int getCurrentDisplayId();

        int getTaskId();

        Rect getMaximumWindowBounds();

        void runOnUiThread(Runnable action);

        void refreshTaskSnapshot();
    }
}
