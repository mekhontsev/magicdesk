package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;

import org.junit.Test;

public final class ImmersiveRequestStateTest {
    @Test
    public void windowedTaskKeepsGeometryAcrossDelayedRestartImmersiveRequest() throws Exception {
        verifyLifecycle("""
                f.beginExplicitWindowedLaunch(42);
                f.handleImmersiveRequest(42, true, true, true);
                SystemClock.now = 30_000L;
                f.noteManualFreeformTransition(42);
                f.handleImmersiveRequest(42, false, true, true);
                SystemClock.now += 150L;
                f.handleImmersiveRequest(42, true, false, true);
                check(state.hasManualImmersiveOverride(), "restart erased windowed choice");
                check(!shouldEnterAppFullscreen(state), "startup request entered fullscreen");
                f.handleImmersiveRequest(42, false, false, true);
                f.handleImmersiveRequest(42, true, false, true);
                check(!state.hasManualImmersiveOverride(), "later request kept override");
                check(shouldEnterAppFullscreen(state), "later deliberate request was ignored");
                """);
    }

    @Test
    public void resizeOfAnExistingTaskDoesNotRequireAnExplicitWindowedLaunch() throws Exception {
        verifyLifecycle("""
                f.handleImmersiveRequest(42, false, true, true);
                f.noteManualFreeformTransition(42);
                for (int restart = 0; restart < 3; restart++) {
                    SystemClock.now += 30_000L;
                    f.handleImmersiveRequest(42, false, true, true);
                    f.handleImmersiveRequest(42, true, false, true);
                    check(state.hasManualImmersiveOverride(), "replacement lost override");
                    check(!shouldEnterAppFullscreen(state), "replacement entered fullscreen");
                }
                """);
    }

    @Test
    public void alreadyImmersiveReplacementDoesNotSwallowTheNextRequest() throws Exception {
        verifyLifecycle("""
                f.noteManualFreeformTransition(42);
                f.handleImmersiveRequest(42, true, true, true);
                check(state.hasManualImmersiveOverride(), "initial request lost override");
                f.handleImmersiveRequest(42, false, false, true);
                f.handleImmersiveRequest(42, true, false, true);
                check(shouldEnterAppFullscreen(state), "later request swallowed");
                """);
    }

    @Test
    public void manualResizeWithoutClientRestartDoesNotArmStartupProtection() throws Exception {
        verifyLifecycle("""
                f.handleImmersiveRequest(42, false, true, true);
                f.noteManualFreeformTransition(42);
                f.handleImmersiveRequest(42, true, false, true);
                check(shouldEnterAppFullscreen(state), "resize without restart blocked request");
                """);
    }

    @Test
    public void unprotectedClientStillEntersFullscreen() throws Exception {
        verifyLifecycle("""
                f.handleImmersiveRequest(42, false, true, true);
                f.handleImmersiveRequest(42, true, false, true);
                check(shouldEnterAppFullscreen(state), "unprotected request was ignored");
                """);
    }

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

    private static void verifyLifecycle(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class SystemClock {
                    static long now = 1_000L;
                    static long uptimeMillis() { return now; }
                }
                static class Log { static void i(String tag, String message) {} }
                static class DesktopWindowTransitionProvenance {
                    static void noteApplicationRequest(int task, boolean requested) {}
                }
                static class RuntimeState { void scheduleRefresh() {} }
                static class DesktopTaskRuntimeState {
                    Boolean mImmersiveRequested;
                    boolean mImmersiveRequestForeground, mManualImmersiveOverride;
                    boolean mAppRequestedFullscreen, mStartupWindowed;
                    long mStartupWindowedDeadlineUptimeMillis = Long.MAX_VALUE;
                    boolean isFullscreenTransition() { return false; }
                """ + RuntimeSourceFixture.methods("DesktopTaskRuntimeState",
                "updateImmersiveObservation", "clearImmersiveRequested",
                "isImmersiveRequested", "isImmersiveRequestForeground",
                "isAppRequestedFullscreen", "hasManualImmersiveOverride",
                "setManualImmersiveOverride", "setStartupWindowed",
                "observeStartupWindowedInitialSample", "consumeStartupWindowed") + """
                }
                static class States {
                    final DesktopTaskRuntimeState state = new DesktopTaskRuntimeState();
                    DesktopTaskRuntimeState state(int task) { return state; }
                }
                static final String TAG = "fixture";
                static final long STARTUP_IMMERSIVE_SETTLE_MILLIS = 1_000L;
                final States mTaskStates = new States();
                final RuntimeState mRuntimeState = new RuntimeState();
                public static void verify() {
                    Fixture f = new Fixture();
                    DesktopTaskRuntimeState state = f.mTaskStates.state(42);
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "DesktopWindowTransitionController", "handleImmersiveRequest",
                "noteManualFreeformTransition", "beginExplicitWindowedLaunch",
                "shouldIgnoreBackgroundImmersiveExit", "isNewImmersiveRequest",
                "shouldReconcileInitialImmersiveSample", "shouldClearManualImmersiveOverride",
                "shouldEnterAppFullscreen"));
    }
}
