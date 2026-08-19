package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WindowedTaskActivityStateTest {
    private static final String ROOT = "com.example.app";
    private static final String MAIN = "com.example.app/.MainActivity";
    private static final String PERMISSION =
            "com.android.permissioncontroller/.GrantPermissionsActivity";

    @Test
    public void restoresUnexpectedFullscreenDuringActivityHandoff() {
        final WindowedTaskActivityState state = state();
        state.arm(MAIN, ROOT);

        assertEquals(
                WindowedTaskActivityState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false));
        assertEquals(
                WindowedTaskActivityState.Decision.NONE,
                state.observe(MAIN, ROOT, 1, false));
        assertEquals(
                WindowedTaskActivityState.Decision.SETTLED,
                state.observe(MAIN, ROOT, 5, false));
        assertFalse(state.isArmed());
    }

    @Test
    public void followsTransientActivityInsideSameTask() {
        final WindowedTaskActivityState state = state();
        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                WindowedTaskActivityState.Decision.RESTORE_FREEFORM,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        1,
                        false));
    }

    @Test
    public void allowsExplicitImmersiveRequest() {
        final WindowedTaskActivityState state = state();
        state.arm(MAIN, ROOT);

        assertEquals(
                WindowedTaskActivityState.Decision.ALLOW_IMMERSIVE,
                state.observe(MAIN, ROOT, 1, true));
        assertFalse(state.isArmed());
    }

    @Test
    public void ignoresUnrelatedModeChangesWithoutActivityStart() {
        final WindowedTaskActivityState state = state();

        assertEquals(
                WindowedTaskActivityState.Decision.NONE,
                state.observe(MAIN, ROOT, 1, false));
        assertFalse(state.isArmed());
    }

    @Test
    public void failedCorrectionCanBeRetriedByNextObservation() {
        final WindowedTaskActivityState state = state();
        state.arm(MAIN, ROOT);
        assertEquals(
                WindowedTaskActivityState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false));

        state.correctionFailed();

        assertEquals(
                WindowedTaskActivityState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false));
        assertTrue(state.isArmed());
    }

    @Test
    public void nextActivityDoesNotDuplicateInFlightCorrection() {
        final WindowedTaskActivityState state = state();
        state.arm(MAIN, ROOT);
        assertEquals(
                WindowedTaskActivityState.Decision.RESTORE_FREEFORM,
                state.observe(MAIN, ROOT, 1, false));

        state.arm(PERMISSION, "com.android.permissioncontroller");

        assertEquals(
                WindowedTaskActivityState.Decision.NONE,
                state.observe(
                        PERMISSION,
                        "com.android.permissioncontroller",
                        1,
                        false));
    }

    private static WindowedTaskActivityState state() {
        return new WindowedTaskActivityState(ROOT);
    }
}
