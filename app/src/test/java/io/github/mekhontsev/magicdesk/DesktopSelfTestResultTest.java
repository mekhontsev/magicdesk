package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class DesktopSelfTestResultTest {
    @Test
    public void formatsStableSummaryAndFailureOutcome() {
        final DesktopSelfTestResult result = new DesktopSelfTestResult(1_000L);
        result.add(DesktopSelfTestResult.State.PASS,
                "PASS-001", "Available API", "present");
        result.add(DesktopSelfTestResult.State.WARN,
                "WARN-001", "Optional API", "missing\non this build");
        result.add(DesktopSelfTestResult.State.FAIL,
                "FAIL-001", "Window transition", "timed out");
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "DEVICE-001", "Physical display", "not connected");
        result.finish(1_250L);

        assertEquals("1 passed, 1 warnings, 1 failed, 1 not tested",
                result.summary());
        assertTrue(result.hasFailures());
        final String report = result.format();
        assertTrue(report.contains("Duration: 250 ms"));
        assertTrue(report.contains("Outcome: FAIL"));
        assertTrue(report.contains(
                "WARN [WARN-001] Optional API: missing on this build"));
    }

    @Test
    public void warningDoesNotBecomeFailure() {
        final DesktopSelfTestResult result = new DesktopSelfTestResult(2_000L);
        result.add(DesktopSelfTestResult.State.PASS,
                "PASS-001", "Core", "ready");
        result.add(DesktopSelfTestResult.State.WARN,
                "WARN-001", "Optional", "missing");
        result.finish(2_001L);

        assertFalse(result.hasFailures());
        assertTrue(result.format().contains("Outcome: WARN"));
    }

    @Test
    public void failFastStopsOnceAfterRecordingTheFailure() {
        final DesktopSelfTestResult result = new DesktopSelfTestResult(3_000L);
        result.arm(DesktopSelfTestExecutionPolicy.FAIL_FAST);

        try {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "FAIL-FAST-001", "First failure", "failed");
            fail("fail-fast did not stop the workflow");
        } catch (DesktopSelfTestResult.StopAfterFirstFailure stopped) {
            assertEquals("FAIL-FAST-001", stopped.code);
        }

        result.add(DesktopSelfTestResult.State.FAIL,
                "CLEANUP-001", "Cleanup failure", "also recorded");
        assertEquals(2, result.count(DesktopSelfTestResult.State.FAIL));
    }
}
