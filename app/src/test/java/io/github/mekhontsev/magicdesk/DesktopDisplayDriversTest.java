package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopDisplayDriversTest {
    @Test
    public void registryProvidesOneDriverPerTargetKind() {
        assertTrue(DesktopDisplayDrivers.isExternalDesktopSupported());
        for (final DesktopDisplayOutput.Kind kind
                : DesktopDisplayOutput.Kind.values()) {
            final DesktopDisplayDriver driver =
                    DesktopDisplayDrivers.forKind(kind);

            assertEquals(kind, driver.kind());
            assertSame(driver, DesktopDisplayDrivers.forKind(kind));
        }
    }

    @Test
    public void targetFactoriesEnforceEachDriverEnvironment() {
        assertEquals(
                DesktopDisplayOutput.Kind.BUILT_IN,
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayOutput.Kind.BUILT_IN)
                        .target(0).output.kind);
        assertEquals(
                DesktopDisplayOutput.Kind.WIRED,
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayOutput.Kind.WIRED)
                        .target(3).output.kind);
        assertEquals(
                DesktopDisplayOutput.Kind.WIRELESS,
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayOutput.Kind.WIRELESS)
                        .target(4).output.kind);
        assertEquals(
                DesktopDisplayOutput.Kind.SIMULATED,
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayOutput.Kind.SIMULATED)
                        .target(195).output.kind);
    }

    @Test
    public void featureMatrixMatchesDisplayBehavior() {
        final DesktopDisplayFeatures phone = features(
                DesktopDisplayOutput.Kind.BUILT_IN);
        final DesktopDisplayFeatures wired = features(
                DesktopDisplayOutput.Kind.WIRED);
        final DesktopDisplayFeatures wireless = features(
                DesktopDisplayOutput.Kind.WIRELESS);
        final DesktopDisplayFeatures simulated = features(
                DesktopDisplayOutput.Kind.SIMULATED);

        assertFalse(phone.phoneScreenControl);
        assertFalse(simulated.phoneScreenControl);
        assertTrue(wired.phoneScreenControl);
        assertTrue(wireless.phoneScreenControl);

        assertFalse(phone.phoneTouchpad);
        assertTrue(wired.phoneTouchpad);
        assertTrue(wireless.phoneTouchpad);
        assertTrue(simulated.phoneTouchpad);
    }

    @Test
    public void removalPolicyPreservesPhysicalAndSimulatedSemantics() {
        final DesktopDisplayTarget wired = DesktopDisplayTarget.wired(3);
        final DesktopDisplayTarget wireless = DesktopDisplayTarget.wireless(4);
        final DesktopDisplayTarget simulated =
                DesktopDisplayTarget.simulated(195);

        assertTrue(driver(wired).isSessionDisplayRemoval(
                wired, 3, false));
        assertFalse(driver(wired).isSessionDisplayRemoval(
                wired, 4, true));
        assertTrue(driver(wireless).isSessionDisplayRemoval(
                wireless, 4, false));
        assertFalse(driver(wireless).isSessionDisplayRemoval(
                wireless, 3, true));
        assertTrue(driver(simulated).isSessionDisplayRemoval(
                simulated, 195, true));
        assertFalse(driver(simulated).isSessionDisplayRemoval(
                simulated, 195, false));
    }

    @Test
    public void captureUsesTheTaskHostingDisplayForEveryTarget() {
        final DesktopDisplayTarget wired = DesktopDisplayTarget.restore(
                DesktopDisplayOutput.Kind.WIRED, 287, 265, "display:wired:local:21",
                DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING);

        assertEquals(287, DesktopCaptureTarget.sourceFor(wired).logicalDisplayId);
        assertEquals(
                0,
                DesktopCaptureTarget.sourceFor(DesktopDisplayTarget.phone()).logicalDisplayId);
        assertEquals(
                8,
                DesktopCaptureTarget.sourceFor(DesktopDisplayTarget.wireless(8)).logicalDisplayId);
    }

    private static DesktopDisplayFeatures features(
            final DesktopDisplayOutput.Kind kind) {
        return DesktopDisplayDrivers.forKind(kind).features();
    }

    private static DesktopDisplayDriver driver(
            final DesktopDisplayTarget target) {
        return DesktopDisplayDrivers.forTarget(target);
    }
}
