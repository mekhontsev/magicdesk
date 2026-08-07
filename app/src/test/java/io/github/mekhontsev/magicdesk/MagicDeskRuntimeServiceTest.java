package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MagicDeskRuntimeServiceTest {
    @Test
    public void recognizesOwnedDisplayAfterTargetMetadataWasLost() {
        assertTrue(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true, 100, 100, null));
    }

    @Test
    public void recognizesKnownWirelessDisplayAfterOwnershipWasCleared() {
        assertTrue(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true,
                100,
                -1,
                DesktopDisplayTarget.Kind.WIRELESS));
    }

    @Test
    public void ignoresSimulatedAndUnownedDisplays() {
        assertFalse(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true,
                100,
                100,
                DesktopDisplayTarget.Kind.SIMULATED));
        assertFalse(MagicDeskRuntimeService.isExternalDesktopRemoval(
                true, 100, -1, null));
    }
}
