package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeDesktopSessionCoordinatorTest {
    @Test
    public void recognizesOwnedDisplayAfterTargetMetadataWasLost() {
        assertTrue(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true, 100, 100, null, false));
    }

    @Test
    public void recognizesKnownWirelessDisplayAfterOwnershipWasCleared() {
        assertTrue(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true,
                100,
                -1,
                DesktopDisplayTarget.wireless(100),
                false));
    }

    @Test
    public void recognizesOnlyActiveSimulatedDisplayRemoval() {
        assertTrue(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true,
                100,
                100,
                DesktopDisplayTarget.simulated(100),
                true));
        assertFalse(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true,
                100,
                100,
                DesktopDisplayTarget.simulated(100),
                false));
    }

    @Test
    public void ignoresUnownedDisplayRemoval() {
        assertFalse(RuntimeDesktopSessionCoordinator.isExternalDesktopRemoval(
                true, 100, -1, null, false));
    }

    @Test
    public void phoneRecoveryCompletesOnlyAfterFinalSettledPass() {
        assertFalse(RuntimeDesktopSessionCoordinator.isPhoneRecoveryComplete(
                false, false));
        assertFalse(RuntimeDesktopSessionCoordinator.isPhoneRecoveryComplete(
                true, true));
        assertTrue(RuntimeDesktopSessionCoordinator.isPhoneRecoveryComplete(
                true, false));
    }
}
