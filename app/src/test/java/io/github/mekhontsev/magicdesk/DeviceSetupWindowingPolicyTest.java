package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeviceSetupWindowingPolicyTest {
    @Test
    public void userWindowingOptionsRequireBothSettings() {
        assertTrue(DeviceSetupManager.hasRequiredWindowingSettings(true, true));
        assertFalse(DeviceSetupManager.hasRequiredWindowingSettings(true, false));
        assertFalse(DeviceSetupManager.hasRequiredWindowingSettings(false, true));
        assertFalse(DeviceSetupManager.hasRequiredWindowingSettings(false, false));
    }

    @Test
    public void fullConfigurationRequiresUserAndPrivilegedSettings() {
        assertTrue(DeviceSetupManager.isFullWindowingConfigurationReady(
                true, true, true, true));
        assertFalse(DeviceSetupManager.isFullWindowingConfigurationReady(
                true, true, false, true));
        assertFalse(DeviceSetupManager.isFullWindowingConfigurationReady(
                true, true, true, false));
        assertFalse(DeviceSetupManager.isFullWindowingConfigurationReady(
                false, true, true, true));
    }
}
