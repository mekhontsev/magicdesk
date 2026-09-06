package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeviceSetupWindowingPolicyTest {
    @Test
    public void setupReusesBoundedCommandOwnerAndValidatedAudit() throws Exception {
        final String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DeviceSetupManager.java"));
        assertTrue(source.contains("BoundedProcessRunner.run("));
        assertFalse(source.contains("process.waitFor()"));
        final String configure = source.substring(source.indexOf("static Audit configure("),
                source.indexOf("static Audit restoreDefaults("));
        assertTrue(configure.contains("return after;"));
        assertFalse(configure.contains("return audit("));
    }

    @Test
    public void userWindowingOptionsRequireBothSettings() {
        assertTrue(DeviceSetupManager.hasRequiredWindowingSettings(true, true));
        assertFalse(DeviceSetupManager.hasRequiredWindowingSettings(true, false));
        assertFalse(DeviceSetupManager.hasRequiredWindowingSettings(false, true));
        assertFalse(DeviceSetupManager.hasRequiredWindowingSettings(false, false));
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
