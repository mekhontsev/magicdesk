package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;

import org.junit.Test;

public final class ImmersiveRequestStateTest {
    @Test
    public void foregroundObservationOwnsAutomaticFullscreenEligibility() {
        final DesktopTaskRuntimeState state =
                new DesktopTaskRuntimeState(42);

        state.updateImmersiveObservation(true, false);
        assertFalse(DesktopWindowTransitionController
                .shouldEnterAppFullscreen(state));

        state.updateImmersiveObservation(true, true);
        assertTrue(DesktopWindowTransitionController
                .shouldEnterAppFullscreen(state));
    }

    @Test
    public void changedProcessStartsANewClientSample() {
        assertTrue(FrameworkTaskObservationSource.isInitialClientSample(
                Integer.valueOf(1), Integer.valueOf(100), Integer.valueOf(101)));
        assertFalse(FrameworkTaskObservationSource.isInitialClientSample(
                Integer.valueOf(1), Integer.valueOf(100), Integer.valueOf(100)));
    }

    @Test
    public void missingProcessIdentityDoesNotInventAClientRestart() {
        assertTrue(FrameworkTaskObservationSource.isInitialClientSample(
                null, null, null));
        assertFalse(FrameworkTaskObservationSource.isInitialClientSample(
                Integer.valueOf(1), Integer.valueOf(100), null));
    }

    @Test
    public void onlySameClientFalseToTrueIsANewImmersiveRequest() {
        assertTrue(DesktopWindowTransitionController.isNewImmersiveRequest(
                Boolean.FALSE, true, false));
        assertFalse(DesktopWindowTransitionController.isNewImmersiveRequest(
                Boolean.FALSE, true, true));
        assertFalse(DesktopWindowTransitionController.isNewImmersiveRequest(
                Boolean.TRUE, true, false));
        assertFalse(DesktopWindowTransitionController.isNewImmersiveRequest(
                Boolean.TRUE, false, false));
    }

    @Test
    public void explicitWindowedLaunchKeepsOverrideForStartupRequest() {
        assertFalse(DesktopWindowTransitionController
                .shouldClearManualImmersiveOverride(
                        true, true));
        assertTrue(DesktopWindowTransitionController
                .shouldClearManualImmersiveOverride(
                        true, false));
    }

    @Test
    public void repeatedInitialSampleReconcilesKnownImmersiveRequest() {
        assertTrue(DesktopWindowTransitionController
                .shouldReconcileInitialImmersiveSample(
                        Boolean.TRUE, true, false));
        assertTrue(DesktopWindowTransitionController
                .shouldReconcileInitialImmersiveSample(
                        Boolean.FALSE, false, true));
    }

    @Test
    public void firstClientSampleDoesNotInventImmersiveRequest() {
        assertFalse(DesktopWindowTransitionController
                .shouldReconcileInitialImmersiveSample(
                        null, true, false));
        assertFalse(DesktopWindowTransitionController
                .shouldReconcileInitialImmersiveSample(
                        Boolean.FALSE, false, false));
    }

    @Test
    public void ignoresOnlyBackgroundImmersiveExit() {
        assertTrue(DesktopWindowTransitionController
                .shouldIgnoreBackgroundImmersiveExit(false, false, false));
        assertFalse(DesktopWindowTransitionController
                .shouldIgnoreBackgroundImmersiveExit(false, false, true));
        assertFalse(DesktopWindowTransitionController
                .shouldIgnoreBackgroundImmersiveExit(false, true, false));
        assertFalse(DesktopWindowTransitionController
                .shouldIgnoreBackgroundImmersiveExit(true, false, false));
    }

    @Test
    public void holdsFullscreenWhileFixedOrientationIsRequested() {
        assertTrue(DesktopWindowTransitionController
                .hasFixedRequestedOrientation(
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE));
        assertTrue(DesktopWindowTransitionController
                .hasFixedRequestedOrientation(
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT));
        assertFalse(DesktopWindowTransitionController
                .hasFixedRequestedOrientation(
                        ActivityInfo.SCREEN_ORIENTATION_USER));
    }

}
