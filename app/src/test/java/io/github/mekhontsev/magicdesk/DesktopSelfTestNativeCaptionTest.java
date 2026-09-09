package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.platform.android.GenericAndroidPlatformDriver;

import org.junit.Test;

public final class DesktopSelfTestNativeCaptionTest {
    @Test
    public void uncoveredScenarioRecordsOnlyItsOwnDependentChecks() {
        final DesktopSelfTestResult result = new DesktopSelfTestResult(1_000L);
        final PlatformDiagnostics diagnostics =
                new GenericAndroidPlatformDriver().diagnostics();

        assertFalse(DesktopSelfTestInputSuite.prepareNativeCaptionPlacementTests(
                result, diagnostics));

        assertEquals(5, result.count(DesktopSelfTestResult.State.NOT_TESTED));
        assertEquals(0, result.count(DesktopSelfTestResult.State.PASS));
        assertEquals(0, result.count(DesktopSelfTestResult.State.WARN));
        assertEquals(0, result.count(DesktopSelfTestResult.State.FAIL));
        final String report = result.format();
        for (final String code : new String[] {
                "NATIVE-SNAP-001", "NATIVE-SNAP-002", "NATIVE-SNAP-003",
                "FOCUS-006", "FOCUS-007"}) {
            assertTrue(report.contains("NOT_TESTED [" + code + "]"));
        }
        assertTrue(report.contains(
                "no verified native caption snap scenario for this platform"));
        assertFalse(report.contains("FOCUS-005"));
        assertFalse(report.contains("FOCUS-008"));
        assertFalse(report.contains("MAXIMIZED-ALT-TAB"));
    }

    @Test
    public void coveredScenarioStillRequiresActualChecksToPass() {
        final DesktopSelfTestResult result = new DesktopSelfTestResult(1_000L);
        final PlatformDriver driver = PlatformDrivers.resolve(
                new PlatformDevice("nubia", "nubia", "fixture", "fixture",
                        "fixture", "fixture", 35), "", true);

        assertTrue(DesktopSelfTestInputSuite.prepareNativeCaptionPlacementTests(
                result, driver.diagnostics()));

        for (final DesktopSelfTestResult.State state
                : DesktopSelfTestResult.State.values()) {
            assertEquals(0, result.count(state));
        }
    }

    @Test
    public void skippedScenarioDoesNotDisarmFailFast() {
        final DesktopSelfTestResult result = new DesktopSelfTestResult(1_000L);
        result.arm(DesktopSelfTestExecutionPolicy.FAIL_FAST);
        assertFalse(DesktopSelfTestInputSuite.prepareNativeCaptionPlacementTests(
                result, new GenericAndroidPlatformDriver().diagnostics()));

        final DesktopSelfTestResult.StopAfterFirstFailure failure = assertThrows(
                DesktopSelfTestResult.StopAfterFirstFailure.class,
                () -> result.add(DesktopSelfTestResult.State.FAIL,
                        "FOCUS-008", "Focus fixture", "failed"));
        assertEquals("FOCUS-008", failure.code);
        assertEquals(1, result.count(DesktopSelfTestResult.State.FAIL));
    }
}
