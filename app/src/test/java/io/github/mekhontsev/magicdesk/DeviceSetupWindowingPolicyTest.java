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
        assertTrue(configure.indexOf("savePendingReboot(preferences, before.bootId)")
                < configure.indexOf("ShellAccess.run(command)"));
        assertFalse(configure.contains("desktopMode"));
        assertFalse(configure.contains("SystemDesktopModeSetting"));
    }

    @Test
    public void commonWindowingRequiresOnlyFreeformAndResizing() {
        for (int enabled = 0; enabled < 4; enabled++) {
            assertEquals("enabled settings mask=" + enabled, enabled == 3,
                    DeviceSetupManager.hasRequiredWindowingSettings(
                            (enabled & 1) != 0,
                            (enabled & 2) != 0));
        }
    }

    @Test
    public void completeSetupDoesNotRequestAnotherWrite() {
        assertEquals("", DeviceSetupManager.globalSettingsCommand(true, true));
    }

    @Test
    public void preparationWritesOnlyMissingSettings() {
        assertEquals(
                "/system/bin/settings put global enable_freeform_support 1",
                DeviceSetupManager.globalSettingsCommand(false, true));
        assertEquals(
                "/system/bin/settings put global force_resizable_activities 1",
                DeviceSetupManager.globalSettingsCommand(true, false));
        assertEquals(
                "/system/bin/settings put global enable_freeform_support 1"
                        + " && /system/bin/settings put global force_resizable_activities 1",
                DeviceSetupManager.globalSettingsCommand(false, false));
    }

    @Test
    public void defaultsRemoveOverridesWithoutAssumedValues() {
        assertEquals(
                "/system/bin/settings delete global enable_freeform_support"
                        + " && /system/bin/settings delete global "
                        + "force_resizable_activities"
                        + " && /system/bin/settings delete global "
                        + "force_desktop_mode_on_external_displays"
                        + " && /system/bin/wm size reset -d 0"
                        + " && /system/bin/wm density reset -d 0"
                        + " && /system/bin/wm scaling auto -d 0",
                DeviceSetupManager.defaultsCommand());
    }
}
