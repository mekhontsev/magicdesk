package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class DesktopSelfTestStepsTest {
    @Test
    public void fullRunContinuesOnlyAfterVerifiedScenarioCleanup() throws Exception {
        final DesktopSelfTestResult result = result(DesktopSelfTestExecutionPolicy.FULL);
        final List<String> events = new ArrayList<>();
        DesktopSelfTestSteps.scenario(result, "SCENARIO", "Fixture", () -> {
            DesktopSelfTestSteps.require(result, "FIRST", "First", () -> {
                events.add("failure");
                throw new IOException("unsupported request");
            });
            events.add("dependent");
        }, () -> {
            events.add("cleanup");
            return "verified";
        });
        events.add("next");
        assertEquals(List.of("failure", "cleanup", "next"), events);
        assertEquals(1, result.count(DesktopSelfTestResult.State.FAIL));
        assertTrue(result.format().contains("not reached after FIRST"));
        assertTrue(result.format().contains("PASS [SCENARIO-CLEANUP]"));
    }

    @Test
    public void successfulScenarioStillVerifiesCleanup() throws Exception {
        final DesktopSelfTestResult result = result(DesktopSelfTestExecutionPolicy.FULL);
        final List<String> events = new ArrayList<>();
        DesktopSelfTestSteps.scenario(result, "SCENARIO", "Fixture",
                () -> events.add("body"), () -> {
                    events.add("cleanup");
                    return "verified";
                });
        assertEquals(List.of("body", "cleanup"), events);
        assertEquals(1, result.count(DesktopSelfTestResult.State.PASS));
        assertEquals(0, result.count(DesktopSelfTestResult.State.NOT_TESTED));
    }

    @Test
    public void cleanupFailureAbortsInsteadOfContaminatingAnotherScenario() throws Exception {
        final DesktopSelfTestResult result = result(DesktopSelfTestExecutionPolicy.FULL);
        try {
            DesktopSelfTestSteps.scenario(result, "SCENARIO", "Fixture", () -> {
                DesktopSelfTestSteps.failAndAbort(result, "FIRST", "First", "failed");
            }, () -> { throw new IOException("fixture remained"); });
            fail("continued after failed cleanup");
        } catch (DesktopSelfTestSteps.AbortSelfTest failure) {
            assertEquals("SCENARIO-CLEANUP", failure.code);
        }
        assertEquals(2, result.count(DesktopSelfTestResult.State.FAIL));
        assertTrue(result.format().contains("fixture remained"));
    }

    @Test
    public void failFastLeavesCleanupToTheGlobalFinalizer() throws Exception {
        final DesktopSelfTestResult result = result(DesktopSelfTestExecutionPolicy.FAIL_FAST);
        final boolean[] localCleanup = {false};
        try {
            DesktopSelfTestSteps.scenario(result, "SCENARIO", "Fixture", () -> {
                DesktopSelfTestSteps.require(result, "FIRST", "First", () -> {
                    throw new IOException("failed");
                });
            }, () -> { localCleanup[0] = true; return "verified"; });
            fail("fail-fast was swallowed");
        } catch (DesktopSelfTestResult.StopAfterFirstFailure failure) {
            assertEquals("FIRST", failure.code);
        }
        assertFalse(localCleanup[0]);
        assertEquals(1, result.count(DesktopSelfTestResult.State.FAIL));
        assertEquals(0, result.count(DesktopSelfTestResult.State.NOT_TESTED));
    }

    @Test
    public void cancellationDoesNotResumeTheWindowWorkflow() throws Exception {
        final DesktopSelfTestResult result = result(DesktopSelfTestExecutionPolicy.FULL);
        final boolean[] localCleanup = {false};
        try {
            DesktopSelfTestSteps.scenario(result, "SCENARIO", "Fixture",
                    () -> { throw new DesktopSelfTestRunState.Cancelled(); },
                    () -> { localCleanup[0] = true; return "verified"; });
            fail("cancellation was swallowed");
        } catch (DesktopSelfTestRunState.Cancelled expected) {
            assertFalse(localCleanup[0]);
        }
        assertEquals(0, result.count(DesktopSelfTestResult.State.FAIL));
    }

    private static DesktopSelfTestResult result(final DesktopSelfTestExecutionPolicy policy) {
        final DesktopSelfTestResult result = new DesktopSelfTestResult(1_000L);
        result.arm(policy);
        return result;
    }
}
