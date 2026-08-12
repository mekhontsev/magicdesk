package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MagicDeskRuntimeServiceTest {
    @Test
    public void recognizesOwnedDisplayAfterTargetMetadataWasLost() {
        assertTrue(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true, 100, 100, null, false));
    }

    @Test
    public void recognizesKnownWirelessDisplayAfterOwnershipWasCleared() {
        assertTrue(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true,
                100,
                -1,
                DesktopDisplayTarget.wireless(100),
                false));
    }

    @Test
    public void recognizesOnlyActiveSimulatedDisplayRemoval() {
        assertTrue(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true,
                100,
                100,
                DesktopDisplayTarget.simulated(100),
                true));
        assertFalse(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true,
                100,
                100,
                DesktopDisplayTarget.simulated(100),
                false));
    }

    @Test
    public void ignoresUnownedDisplayRemoval() {
        assertFalse(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true, 100, -1, null, false));
    }

    @Test
    public void phoneRecoveryCompletesOnlyAfterFinalSettledPass() {
        assertFalse(MagicDeskRuntimeService.isPhoneRecoveryComplete(
                false, false));
        assertFalse(MagicDeskRuntimeService.isPhoneRecoveryComplete(
                true, true));
        assertTrue(MagicDeskRuntimeService.isPhoneRecoveryComplete(
                true, false));
    }
}
