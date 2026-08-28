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
        for (final DesktopDisplayTarget.Kind kind
                : DesktopDisplayTarget.Kind.values()) {
            final DesktopDisplayDriver driver =
                    DesktopDisplayDrivers.forKind(kind);

            assertEquals(kind, driver.kind());
            assertSame(driver, DesktopDisplayDrivers.forKind(kind));
        }
    }

    @Test
    public void targetFactoriesEnforceEachDriverEnvironment() {
        assertEquals(
                DesktopDisplayTarget.Kind.PHONE,
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayTarget.Kind.PHONE)
                        .target(0).kind);
        assertEquals(
                DesktopDisplayTarget.Kind.WIRED,
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayTarget.Kind.WIRED)
                        .target(3).kind);
        assertEquals(
                DesktopDisplayTarget.Kind.WIRELESS,
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayTarget.Kind.WIRELESS)
                        .target(4).kind);
        assertEquals(
                DesktopDisplayTarget.Kind.SIMULATED,
                DesktopDisplayDrivers
                        .forKind(DesktopDisplayTarget.Kind.SIMULATED)
                        .target(195).kind);
    }

    @Test
    public void featureMatrixMatchesDisplayBehavior() {
        final DesktopDisplayFeatures phone = features(
                DesktopDisplayTarget.Kind.PHONE);
        final DesktopDisplayFeatures wired = features(
                DesktopDisplayTarget.Kind.WIRED);
        final DesktopDisplayFeatures wireless = features(
                DesktopDisplayTarget.Kind.WIRELESS);
        final DesktopDisplayFeatures simulated = features(
                DesktopDisplayTarget.Kind.SIMULATED);

        assertEquals(DesktopTaskAreaPolicy.SESSION, phone.taskAreaPolicy);
        assertEquals(DesktopTaskAreaPolicy.INDEPENDENT,
                simulated.taskAreaPolicy);
        assertEquals(DesktopTaskAreaPolicy.INDEPENDENT,
                wired.taskAreaPolicy);
        assertEquals(DesktopTaskAreaPolicy.INDEPENDENT,
                wireless.taskAreaPolicy);

        assertFalse(phone.rootTaskTransfer);
        assertTrue(simulated.rootTaskTransfer);
        assertTrue(wired.rootTaskTransfer);
        assertTrue(wireless.rootTaskTransfer);

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
    public void captureUsesPhysicalProfileBehindWiredVirtualDesktop() {
        final DesktopDisplayTarget wired = DesktopDisplayTarget.wired(287)
                .withProfile(265, "display:wired:local:21");

        assertEquals(265, driver(wired).captureDisplayId(wired));
        assertEquals(
                0,
                driver(DesktopDisplayTarget.phone()).captureDisplayId(
                        DesktopDisplayTarget.phone()));
        assertEquals(
                8,
                driver(DesktopDisplayTarget.wireless(8)).captureDisplayId(
                        DesktopDisplayTarget.wireless(8)));
        final DesktopDisplayTarget directWired =
                DesktopDisplayTarget.wired(9)
                        .withProfile(9, "display:wired:local:22");
        assertEquals(9, driver(directWired).captureDisplayId(directWired));
    }

    private static DesktopDisplayFeatures features(
            final DesktopDisplayTarget.Kind kind) {
        return DesktopDisplayDrivers.forKind(kind).features();
    }

    private static DesktopDisplayDriver driver(
            final DesktopDisplayTarget target) {
        return DesktopDisplayDrivers.forTarget(target);
    }
}
