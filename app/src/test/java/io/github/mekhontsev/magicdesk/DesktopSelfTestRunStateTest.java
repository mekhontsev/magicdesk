package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public final class DesktopSelfTestRunStateTest {
    @Before
    @After
    public void resetRunState() {
        DesktopSelfTestRunState.resetForTests();
    }

    @Test
    public void lifecycleKeepsCurrentAndLastCompletedStageSeparate() {
        final long runId = DesktopSelfTestRunState.beginRequest(
                "simulated", DesktopSelfTestExecutionPolicy.FULL, 100L);
        assertTrue(runId > 0L);
        assertEquals(DesktopSelfTestRunState.State.STARTING,
                DesktopSelfTestRunState.snapshot().state);

        assertEquals(runId, DesktopSelfTestRunState.startRun(
                runId,
                "simulated",
                DesktopSelfTestExecutionPolicy.FULL,
                200L));
        DesktopSelfTestRunState.stage(runId, "WINDOW-001");
        DesktopSelfTestRunState.checkCompleted(runId, "PRECONDITION-001");

        final DesktopSelfTestRunState.Snapshot running =
                DesktopSelfTestRunState.snapshot();
        assertEquals("WINDOW-001", running.stage);
        assertEquals("PRECONDITION-001", running.lastCompletedStage);

        DesktopSelfTestRunState.beginCleanup(runId);
        DesktopSelfTestRunState.checkCompleted(runId, "CLEANUP-001");
        DesktopSelfTestRunState.complete(
                runId, false, true, 300L, "passed", 250L);

        final DesktopSelfTestRunState.Snapshot completed =
                DesktopSelfTestRunState.snapshot();
        assertEquals(DesktopSelfTestRunState.State.COMPLETED, completed.state);
        assertEquals("COMPLETE", completed.stage);
        assertEquals("CLEANUP-001", completed.lastCompletedStage);
        assertFalse(completed.active());
        assertTrue(completed.terminal());
    }

    @Test
    public void cancellationRequiresTheExactActiveRun() {
        final long runId = DesktopSelfTestRunState.beginRequest(
                "wired", DesktopSelfTestExecutionPolicy.FAIL_FAST, 100L);

        assertEquals(DesktopSelfTestRunState.CancellationStatus.RUN_MISMATCH,
                DesktopSelfTestRunState.requestCancellation(0L));
        assertEquals(DesktopSelfTestRunState.CancellationStatus.RUN_MISMATCH,
                DesktopSelfTestRunState.requestCancellation(runId + 1L));
        assertEquals(DesktopSelfTestRunState.CancellationStatus.ACCEPTED,
                DesktopSelfTestRunState.requestCancellation(runId));
        assertEquals(
                DesktopSelfTestRunState.CancellationStatus.ALREADY_REQUESTED,
                DesktopSelfTestRunState.requestCancellation(runId));

        DesktopSelfTestRunState.startRun(
                runId, "wired", DesktopSelfTestExecutionPolicy.FAIL_FAST, 200L);
        try {
            DesktopSelfTestRunState.checkpoint();
            fail("cancellation checkpoint did not stop the run");
        } catch (DesktopSelfTestRunState.Cancelled expected) {
            assertTrue(DesktopSelfTestRunState.isCancellationRequested());
        }
    }

    @Test
    public void preparationCancellationInvokesRegisteredHandler() {
        final long runId = DesktopSelfTestRunState.beginRequest(
                "wireless", DesktopSelfTestExecutionPolicy.FULL, 100L);
        final AtomicBoolean invoked = new AtomicBoolean();
        assertTrue(DesktopSelfTestRunState
                .registerPreparationCancellationHandler(
                        runId, () -> invoked.set(true)));

        assertEquals(DesktopSelfTestRunState.CancellationStatus.ACCEPTED,
                DesktopSelfTestRunState.requestCancellation(runId));
        assertTrue(invoked.get());

        DesktopSelfTestRunState.complete(
                runId, true, true, 200L, "cancelled", 0L);
        assertEquals(DesktopSelfTestRunState.State.CANCELLED,
                DesktopSelfTestRunState.snapshot().state);
    }

    @Test
    public void isolatedDesktopSessionClosureCancelsTheActiveRun() {
        final long runId = DesktopSelfTestRunState.startRun(
                0L, "phone", DesktopSelfTestExecutionPolicy.FULL, 100L);

        DesktopSelfTestRunState.noteDesktopSessionClosed(
                DesktopSessionPolicy.USER, 0);
        DesktopSelfTestRunState.checkpoint();

        DesktopSelfTestRunState.noteDesktopSessionClosed(
                DesktopSessionPolicy.ISOLATED_SELF_TEST, 0);
        assertTrue(DesktopSelfTestRunState.isCancellationRequested());
        assertEquals("desktop session closed on display 0",
                DesktopSelfTestRunState.snapshot().detail);
        try {
            DesktopSelfTestRunState.checkpoint();
            fail("closed desktop session did not stop the run");
        } catch (DesktopSelfTestRunState.Cancelled expected) {
            assertEquals(runId,
                    DesktopSelfTestRunState.snapshot().runId);
        }
    }

    @Test
    public void desktopSessionClosureDoesNotCancelCleanup() {
        final long runId = DesktopSelfTestRunState.startRun(
                0L, "simulated", DesktopSelfTestExecutionPolicy.FULL, 100L);
        DesktopSelfTestRunState.beginCleanup(runId);

        DesktopSelfTestRunState.noteDesktopSessionClosed(
                DesktopSessionPolicy.ISOLATED_SELF_TEST, 7);

        assertFalse(DesktopSelfTestRunState.isCancellationRequested());
        DesktopSelfTestRunState.checkpoint();
    }

    @Test
    public void cleanupCannotBeCancelledOrCompletedTwice() {
        final long runId = DesktopSelfTestRunState.startRun(
                0L, "simulated", DesktopSelfTestExecutionPolicy.FULL, 100L);
        DesktopSelfTestRunState.beginCleanup(runId);

        assertEquals(
                DesktopSelfTestRunState.CancellationStatus.CLEANUP_STARTED,
                DesktopSelfTestRunState.requestCancellation(runId));
        DesktopSelfTestRunState.complete(
                runId, false, true, 200L, "passed", 150L);
        DesktopSelfTestRunState.complete(
                runId, true, true, 300L, "late cancellation", 150L);

        assertEquals(DesktopSelfTestRunState.State.COMPLETED,
                DesktopSelfTestRunState.snapshot().state);
        assertEquals("passed", DesktopSelfTestRunState.snapshot().detail);
    }

    @Test
    public void resultUpdatesOnlyItsBoundRun() {
        final long runId = DesktopSelfTestRunState.startRun(
                0L, "phone", DesktopSelfTestExecutionPolicy.FULL, 100L);

        final DesktopSelfTestResult unrelated =
                new DesktopSelfTestResult(100L);
        unrelated.add(DesktopSelfTestResult.State.FAIL,
                "OTHER-001", "Other result", "ignored by run state");
        assertEquals("", DesktopSelfTestRunState.snapshot().lastCompletedStage);

        final DesktopSelfTestResult bound =
                new DesktopSelfTestResult(100L, runId);
        bound.add(DesktopSelfTestResult.State.PASS,
                "PHONE-001", "Bound result", "recorded");
        assertEquals("PHONE-001",
                DesktopSelfTestRunState.snapshot().lastCompletedStage);
    }
}
