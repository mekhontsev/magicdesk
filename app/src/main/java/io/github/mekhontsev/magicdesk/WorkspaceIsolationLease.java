package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Scopes a self-test desktop session away from the saved user workspace. */
final class WorkspaceIsolationLease implements AutoCloseable {
    private boolean mClosed;
    private boolean mStartAttempted;

    private WorkspaceIsolationLease() {
    }

    static WorkspaceIsolationLease open() {
        return new WorkspaceIsolationLease();
    }

    void showReady(final DesktopDisplayTarget target) throws IOException {
        requireOpen();
        final CountDownLatch completed = new CountDownLatch(1);
        final TaskRepository.ActionResult[] result = new TaskRepository.ActionResult[1];
        final long runId = DesktopSelfTestRunState.snapshot().runId;
        mStartAttempted = true;
        DesktopSelfTestRunState.expectSessionClose(runId, target.workspaceDisplayId);
        try {
            DesktopOperations.showDesktop(target, DesktopSessionPolicy.ISOLATED_SELF_TEST, value -> {
                result[0] = value;
                completed.countDown();
            });
            // EVENT_WAIT: production Start completion, including rollback on failure.
            // Cancellation is checked after completion so cleanup cannot overtake Start.
            if (!completed.await(ExternalDisplayController.START_TIMEOUT_MS * 2L,
                    TimeUnit.MILLISECONDS)) {
                throw new IOException("production desktop start timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("desktop start wait interrupted", error);
        } finally {
            DesktopSelfTestRunState.resumeSessionCloseObservation(runId, target.workspaceDisplayId);
        }
        if (result[0] == null || !result[0].success) {
            throw new IOException("production desktop start failed: "
                    + (result[0] == null ? "no result" : result[0].message));
        }
    }

    boolean startAttempted() {
        return mStartAttempted;
    }

    @Override
    public void close() {
        mClosed = true;
    }

    private void requireOpen() {
        if (mClosed) {
            throw new IllegalStateException(
                    "workspace isolation lease is closed");
        }
    }
}
