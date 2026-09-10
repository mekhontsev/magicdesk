package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskActivityModeStateTest {
    private static final String ROOT = "com.example.app";
    private static final String MAIN = "com.example.app/.MainActivity";
    private static final String PERMISSION =
            "com.android.permissioncontroller/.GrantPermissionsActivity";

    @Test
    public void restoresUnexpectedFullscreenDuringActivityHandoff() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);

        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false, false));
        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 1, false, false));
        assertEquals(
                TaskActivityModeState.Decision.SETTLED,
                state.observe(MAIN, ROOT, 5, false, false));
        assertFalse(state.isArmed());
    }

    @Test
    public void followsTransientActivityInsideSameTask() {
        final TaskActivityModeState state = windowedState();
        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        1,
                        false, false));
    }

    @Test
    public void allowsExplicitImmersiveRequest() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);

        assertEquals(
                TaskActivityModeState.Decision.ALLOW_IMMERSIVE,
                state.observe(MAIN, ROOT, 1, true, false));
        assertFalse(state.isArmed());
    }

    @Test
    public void unavailableImmersiveObservationDoesNotGuess() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);

        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 1, null, false));
        assertTrue(state.isArmed());
    }

    @Test
    public void unavailableObservationDoesNotRestoreForeignTopAfterMatchedHandoff() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);

        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 1, null, false));
        assertTrue(state.isArmed());
        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        1,
                        null, false));
        assertTrue(state.isArmed());
        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false, false));
    }

    @Test
    public void fullscreenPreferenceCorrectionDoesNotRequireImmersiveObservation() {
        final TaskActivityModeState state = fullscreenState();
        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FULLSCREEN,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        5,
                        null, false));
        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        5,
                        null, false));
    }

    @Test
    public void ignoresUnrelatedModeChangesWithoutActivityStart() {
        final TaskActivityModeState state = windowedState();

        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 1, false, false));
        assertFalse(state.isArmed());
    }

    @Test
    public void failedCorrectionCanBeRetriedByNextObservation() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);
        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false, false));

        state.correctionFailed();

        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false, false));
        assertTrue(state.isArmed());
    }

    @Test
    public void nextActivityDoesNotDuplicateInFlightCorrection() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);
        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false, false));

        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        1,
                        false, false));
    }

    @Test
    public void appliedCorrectionDoesNotBlockNextHandoff() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);
        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false, false));

        state.finishHandoff();
        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        1,
                        false, false));
    }

    @Test
    public void restoresUnexpectedFreeformDuringFullscreenHandoff() {
        final TaskActivityModeState state = fullscreenState();
        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FULLSCREEN,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        5,
                        false, false));
        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        5,
                        false, false));
        state.finishHandoff();
        assertFalse(state.isArmed());
    }

    @Test
    public void fullscreenGuardIgnoresManualRestoreWithoutActivityStart() {
        final TaskActivityModeState state = fullscreenState();

        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 5, false, false));
    }

    @Test
    public void restoresBoundsWhenModeRoundTripWasNotObserved() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);
        assertEquals(TaskActivityModeState.Decision.RESTORE_BOUNDS,
                state.observe(MAIN, ROOT, 5, false, true));
        assertEquals(TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 5, false, true));
        state.finishHandoff();
        assertFalse(state.isArmed());
        assertEquals(TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 5, false, true));
    }

    @Test
    public void repeatedStartOfSameActivityCanRestoreBoundsAgain() {
        final TaskActivityModeState state = windowedState();
        for (int launch = 0; launch < 2; launch++) {
            state.arm(MAIN, ROOT);
            assertEquals(TaskActivityModeState.Decision.RESTORE_BOUNDS,
                    state.observe(MAIN, ROOT, 5, false, true));
            state.finishHandoff();
        }
    }

    @Test
    public void boundsRepairRequiresKnownNonImmersiveMatchingActivity() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);
        assertEquals(TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 5, null, true));
        assertEquals(TaskActivityModeState.Decision.NONE,
                state.observe(PERMISSION, "com.android.permissioncontroller",
                        5, false, true));
        assertEquals(TaskActivityModeState.Decision.ALLOW_IMMERSIVE,
                state.observe(MAIN, ROOT, 5, true, true));
        assertFalse(state.isArmed());
    }

    @Test
    public void resizeWithoutLaunchDoesNotArmProtection() {
        final TaskActivityModeState state = windowedState();
        assertEquals(TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 5, false, true));
        assertFalse(state.isArmed());
    }

    private static TaskActivityModeState windowedState() {
        return new TaskActivityModeState(ROOT, 5);
    }

    private static TaskActivityModeState fullscreenState() {
        return new TaskActivityModeState(ROOT, 1);
    }
}
