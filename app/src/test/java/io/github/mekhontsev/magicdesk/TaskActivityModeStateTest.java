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
                state.observe(MAIN, ROOT, 1, false));
        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 1, false));
        assertEquals(
                TaskActivityModeState.Decision.SETTLED,
                state.observe(MAIN, ROOT, 5, false));
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
                        false));
    }

    @Test
    public void allowsExplicitImmersiveRequest() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);

        assertEquals(
                TaskActivityModeState.Decision.ALLOW_IMMERSIVE,
                state.observe(MAIN, ROOT, 1, true));
        assertFalse(state.isArmed());
    }

    @Test
    public void ignoresUnrelatedModeChangesWithoutActivityStart() {
        final TaskActivityModeState state = windowedState();

        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 1, false));
        assertFalse(state.isArmed());
    }

    @Test
    public void failedCorrectionCanBeRetriedByNextObservation() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);
        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false));

        state.correctionFailed();

        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false));
        assertTrue(state.isArmed());
    }

    @Test
    public void nextActivityDoesNotDuplicateInFlightCorrection() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);
        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false));

        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        1,
                        false));
    }

    @Test
    public void appliedCorrectionDoesNotBlockNextHandoff() {
        final TaskActivityModeState state = windowedState();
        state.arm(MAIN, ROOT);
        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false));

        state.correctionApplied();
        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                TaskActivityModeState.Decision.RESTORE_FREEFORM,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        1,
                        false));
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
                        false));
        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        5,
                        false));
        state.correctionApplied();
        assertFalse(state.isArmed());
    }

    @Test
    public void fullscreenGuardIgnoresManualRestoreWithoutActivityStart() {
        final TaskActivityModeState state = fullscreenState();

        assertEquals(
                TaskActivityModeState.Decision.NONE,
                state.observe(MAIN, ROOT, 5, false));
    }

    private static TaskActivityModeState windowedState() {
        return new TaskActivityModeState(ROOT, 5);
    }

    private static TaskActivityModeState fullscreenState() {
        return new TaskActivityModeState(ROOT, 1);
    }
}
