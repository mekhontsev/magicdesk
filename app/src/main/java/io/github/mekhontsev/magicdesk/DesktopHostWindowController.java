package io.github.mekhontsev.magicdesk;

import android.view.View;

/**
 * Keeps MagicDesk's desktop host fullscreen when Android desktop mode tries to
 * inherit a freeform windowing mode from the task that launched it.
 */
final class DesktopHostWindowController {
    private static final String DIAGNOSTIC_CODE = "TASKS-001";

    private final DesktopShellActivity mActivity;
    private int mGeneration;
    private boolean mPending;
    private boolean mAttempted;

    DesktopHostWindowController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void ensureFullscreen() {
        if (mActivity.isActivityUnavailable()) {
            return;
        }
        if (!mActivity.isInMultiWindowMode()) {
            mPending = false;
            mAttempted = false;
            return;
        }
        if (mPending || mAttempted
                || !RuntimeAccess.has(RuntimeAccess.Capability.TASK_CONTROL)) {
            return;
        }

        mPending = true;
        mAttempted = true;
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
                    if (!snapshot.rootAvailable || task == null) {
                        finishIfCurrent(generation);
                        recordFailure(
                                "Could not inspect desktop task " + taskId
                                        + " on display " + displayId
                                        + ": " + snapshot.error);
                        return;
                    }
                    TaskRepository.setAppRequestedFullscreen(task, result ->
                            mActivity.runOnUiThread(() -> {
                                if (!isCurrent(generation)) {
                                    return;
                                }
                                mPending = false;
                                if (!result.success) {
                                    recordFailure(
                                            "Could not make desktop task " + taskId
                                                    + " fullscreen on display "
                                                    + displayId + ": "
                                                    + result.message);
                                    return;
                                }
                                final View decor =
                                        mActivity.getWindow().getDecorView();
                                decor.requestApplyInsets();
                                mActivity.refreshTaskSnapshot();
                            }));
                }));
    }

    void onMultiWindowModeChanged(final boolean inMultiWindowMode) {
        if (!inMultiWindowMode) {
            mGeneration++;
            mPending = false;
            mAttempted = false;
            return;
        }
        ensureFullscreen();
    }

    void release() {
        mGeneration++;
        mPending = false;
    }

    private boolean isCurrent(final int generation) {
        return generation == mGeneration && !mActivity.isActivityUnavailable();
    }

    private void finishIfCurrent(final int generation) {
        if (generation == mGeneration) {
            mPending = false;
        }
    }

    private void recordFailure(final String detail) {
        CompatibilityDiagnostics.record(
                DIAGNOSTIC_CODE,
                "MagicDesk could not make its desktop host fullscreen",
                detail);
    }
}
