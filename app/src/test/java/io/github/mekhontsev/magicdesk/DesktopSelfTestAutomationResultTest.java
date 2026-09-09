package io.github.mekhontsev.magicdesk;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DesktopSelfTestAutomationResultTest {
    @Before @After public void reset() { DesktopSelfTestRunState.resetForTests(); }

    @Test public void currentRunNeverInheritsPreviousReport() throws Exception {
        final long phoneId = start("phone");
        final var phone = new DesktopSelfTestResult(100, phoneId);
        phone.add(DesktopSelfTestResult.State.FAIL, "PHONE-FAIL", "Phone", "known failure");
        phone.finish(200);
        DesktopSelfTestRunState.complete(phoneId, false, false, 200, "failed", 200);
        final var saved = phone.toJson(true);
        final long simulatedId = start("simulated");
        final var response = DesktopAutomationStateReader.selfTestJson(
                DesktopSelfTestRunState.snapshot(), saved);
        final var current = response.getJSONObject("currentRun");
        assertEquals(simulatedId, current.getLong("runId"));
        assertEquals("simulated", current.getString("target"));
        assertEquals(0, current.getJSONArray("checks").length());
        assertFalse(current.has("report"));
        assertEquals(phoneId, response.getJSONObject("lastCompletedResult").getLong("runId"));
        assertEquals("phone", saved.getString("target"));
        assertFalse(DesktopAutomationController.selfTestCompletion(simulatedId,
                DesktopSelfTestRunState.snapshot(), saved).getBoolean("matched"));
        DesktopSelfTestRunState.resetForTests();
        assertTrue(DesktopAutomationController.selfTestCompletion(phoneId,
                DesktopSelfTestRunState.snapshot(), saved).getBoolean("matched"));
        assertFalse(DesktopAutomationController.selfTestCompletion(simulatedId,
                DesktopSelfTestRunState.snapshot(), saved).getBoolean("matched"));
    }

    @Test public void failureIsPublishedBeforeFailFastAndBoundedHistoryRetainsFirstFailure()
            throws Exception {
        final long id = start("simulated");
        final var result = new DesktopSelfTestResult(100, id);
        result.arm(DesktopSelfTestExecutionPolicy.FAIL_FAST);
        assertThrows(DesktopSelfTestResult.StopAfterFirstFailure.class,
                () -> result.add(DesktopSelfTestResult.State.FAIL, "FIRST", "Failure", "detail"));
        for (int i = 0; i < 520; i++) {
            result.add(DesktopSelfTestResult.State.PASS, "CHECK-" + i, "Check", "ready");
        }
        final var data = DesktopSelfTestRunState.snapshot().progress.checksJson();
        assertEquals(512, data.getJSONArray("checks").length());
        assertTrue(data.getBoolean("checksTruncated"));
        assertEquals("FIRST", data.getJSONObject("firstFailure").getString("code"));
        assertFalse(result.toJson(false).has("report"));
    }

    @Test public void expectedDisplayRemovalDoesNotMasqueradeAsCancellation() {
        final long id = start("simulated");
        DesktopSelfTestRunState.expectSessionClose(id, 7);
        DesktopSelfTestRunState.noteDesktopSessionClosed(DesktopSessionPolicy.ISOLATED_SELF_TEST, 7);
        assertFalse(DesktopSelfTestRunState.snapshot().cancellationRequested);
        DesktopSelfTestRunState.complete(id, false, true, 200, "done", 200);
        assertEquals("passed", DesktopSelfTestRunState.snapshot().outcome);
        start("simulated");
        DesktopSelfTestRunState.noteDesktopSessionClosed(DesktopSessionPolicy.ISOLATED_SELF_TEST, 7);
        assertEquals("session_closed", DesktopSelfTestRunState.snapshot().cancellationReason);
    }

    @Test public void expectedRemovalDoesNotSuppressUserCancellationOrOtherDisplayLoss() {
        final long id = start("simulated");
        DesktopSelfTestRunState.expectSessionClose(id, 7);
        DesktopSelfTestRunState.noteDesktopSessionClosed(DesktopSessionPolicy.ISOLATED_SELF_TEST, 8);
        assertTrue(DesktopSelfTestRunState.snapshot().cancellationRequested);
        reset();
        final long second = start("simulated");
        DesktopSelfTestRunState.expectSessionClose(second, 7);
        DesktopSelfTestRunState.requestCancellation(second);
        assertEquals("user", DesktopSelfTestRunState.snapshot().cancellationReason);
        assertThrows(DesktopSelfTestRunState.Cancelled.class, DesktopSelfTestRunState::checkpoint);
    }

    @Test public void preparationFailureHasFailedOutcomeWithoutACompletedCheck() {
        final long id = DesktopSelfTestRunState.beginRequest(
                "phone", DesktopSelfTestExecutionPolicy.FULL, 100);
        DesktopSelfTestRunState.complete(id, false, false, 200, "could not start", 0);
        assertEquals("failed", DesktopSelfTestRunState.snapshot().outcome);
    }

    @Test public void completionWaitDoesNotFinishDuringCleanupEvenWithASavedResult() throws Exception {
        final long id = start("simulated");
        final var result = new DesktopSelfTestResult(100, id);
        result.add(DesktopSelfTestResult.State.FAIL, "CHECK", "Check", "failure");
        DesktopSelfTestRunState.beginCleanup(id);
        result.finish(200);
        final var saved = result.toJson(false);
        final var pending = DesktopAutomationController.selfTestCompletion(
                id, DesktopSelfTestRunState.snapshot(), saved);
        assertTrue(pending.getBoolean("runKnown"));
        assertFalse(pending.getBoolean("matched"));
        assertEquals("cleanup", pending.getString("state"));
        assertEquals("pending", pending.getString("outcome"));
        DesktopSelfTestRunState.complete(id, false, false, 200, "failed", 200);
        final var finished = DesktopAutomationController.selfTestCompletion(
                id, DesktopSelfTestRunState.snapshot(), saved);
        assertTrue(finished.getBoolean("matched"));
        assertEquals("failed", finished.getString("outcome"));
        assertEquals("current_run", finished.getString("source"));
    }

    @Test public void cancelledRunCompletesWithoutReplacingThePreviousResult() throws Exception {
        final long previousId = start("simulated");
        final var previous = new DesktopSelfTestResult(100, previousId);
        previous.finish(200);
        DesktopSelfTestRunState.complete(previousId, false, true, 200, "passed", 200);
        final var saved = previous.toJson(true);
        final long id = start("phone");
        DesktopSelfTestRunState.requestCancellation(id);
        DesktopSelfTestRunState.beginCleanup(id);
        DesktopSelfTestRunState.complete(id, true, false, 300, "cancelled", 200);
        final var finished = DesktopAutomationController.selfTestCompletion(
                id, DesktopSelfTestRunState.snapshot(), saved);
        assertTrue(finished.getBoolean("matched"));
        assertEquals("cancelled", finished.getString("outcome"));
        assertEquals("user", finished.getString("cancellationReason"));
        final var data = DesktopAutomationStateReader.selfTestJson(
                DesktopSelfTestRunState.snapshot(), saved);
        assertEquals(previousId, data.getJSONObject("lastCompletedResult").getLong("runId"));
        assertFalse(data.getJSONObject("currentRun").has("report"));
        DesktopSelfTestRunState.resetForTests();
        assertFalse(DesktopAutomationController.selfTestCompletion(
                id, DesktopSelfTestRunState.snapshot(), saved).getBoolean("runKnown"));
        assertEquals("saved_result", DesktopAutomationController.selfTestCompletion(
                previousId, DesktopSelfTestRunState.snapshot(), saved).getString("source"));
    }

    private static long start(final String target) {
        return DesktopSelfTestRunState.startRun(0, target, DesktopSelfTestExecutionPolicy.FULL, 100);
    }
}
