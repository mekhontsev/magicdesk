package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ChargeSeparationControllerTest {
    @Test
    public void enablingRequiresSupportedPrivilegedPoweredDevice() {
        assertTrue(ChargeSeparationController.canEnable(
                true, true, true, 20));
        assertFalse(ChargeSeparationController.canEnable(
                false, true, true, 80));
        assertFalse(ChargeSeparationController.canEnable(
                true, false, true, 80));
        assertFalse(ChargeSeparationController.canEnable(
                true, true, false, 80));
        assertFalse(ChargeSeparationController.canEnable(
                true, true, true, 19));
    }
}
