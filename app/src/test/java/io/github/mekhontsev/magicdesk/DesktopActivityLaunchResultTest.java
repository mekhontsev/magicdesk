package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopActivityLaunchResultTest {
    @Test
    public void observedTaskIsTheOnlyTaskBearingSuccess() {
        final DesktopActivityLaunchResult result =
                DesktopActivityLaunchResult.observedTask(31, 9, true);

        assertEquals(
                DesktopActivityLaunchResult.Outcome.OBSERVED_TASK,
                result.outcome);
        assertTrue(result.succeeded());
        assertTrue(result.hasObservedTask());
        assertTrue(result.reused);
        assertFalse(result.isDefinitiveFailure());
    }

    @Test
    public void unmanagedAcceptanceCannotMasqueradeAsObservedTask() {
        final DesktopActivityLaunchResult result =
                DesktopActivityLaunchResult.unmanagedAccepted(0);

        assertEquals(
                DesktopActivityLaunchResult.Outcome.UNMANAGED_ACCEPTED,
                result.outcome);
        assertTrue(result.succeeded());
        assertFalse(result.hasObservedTask());
        assertFalse(result.isDefinitiveFailure());
    }

    @Test
    public void failureHasOneDefinitiveOutcome() {
        final DesktopActivityLaunchResult result =
                DesktopActivityLaunchResult.failed("rejected");

        assertEquals(
                DesktopActivityLaunchResult.Outcome.DEFINITIVE_FAILURE,
                result.outcome);
        assertFalse(result.succeeded());
        assertFalse(result.hasObservedTask());
        assertTrue(result.isDefinitiveFailure());
    }
}
