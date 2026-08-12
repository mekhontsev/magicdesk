package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
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
    public void nubiaConfigurationRequiresUserAndPrivilegedSettings() {
        final PlatformWindowingDriver windowing =
                new NubiaWindowingDriver();
        assertTrue(windowing.isReady(
                true, true, true, true));
        assertFalse(windowing.isReady(
                true, true, false, true));
        assertFalse(windowing.isReady(
                true, true, true, false));
        assertFalse(windowing.isReady(
                false, true, true, true));
    }

    @Test
    public void genericConfigurationUsesOnlyStandardAndroidSettings() {
        final PlatformWindowingDriver windowing =
                new GenericAndroidWindowingDriver();
        assertTrue(windowing.isReady(true, true, false, false));
        assertFalse(windowing.isReady(true, false, true, true));
    }

    @Test
    public void defaultsRemoveOverridesWithoutAssumedValues() {
        assertEquals(
                "/system/bin/settings delete global enable_freeform_support"
                        + " && /system/bin/settings delete global "
                        + "force_resizable_activities"
                        + " && /system/bin/wm size reset -d 0"
                        + " && /system/bin/wm density reset -d 0"
                        + " && /system/bin/wm scaling auto -d 0",
                DeviceSetupManager.defaultsCommand());
    }
}
