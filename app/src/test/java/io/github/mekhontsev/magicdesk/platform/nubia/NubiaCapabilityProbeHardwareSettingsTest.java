package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NubiaCapabilityProbeHardwareSettingsTest {
    @Test
    public void reportsControlAndStateSeparately() {
        final RedmagicHardwareSettings.Snapshot settings =
                RedmagicHardwareSettings.parse(
                        completeSettingsOutput());
        final StringBuilder report = new StringBuilder();

        RedmagicHardwareSettings.appendDiagnostics(report, settings, null);

        final String output = report.toString();
        assertTrue(output.contains(
                "hardware.settings.read=available"
                + " | read-only shell settings snapshot\n"));
        assertTrue(output.contains(
                "hardware.cooling.fan.control=available"
                + " | namespace=system\n"));
        assertTrue(output.contains(
                "hardware.cooling.fan.state=available"
                + " | namespace=global; enabled=0\n"));
        assertTrue(output.contains(
                "hardware.cooling.pump.control=available"
                + " | namespace=global\n"));
        assertTrue(output.contains(
                "hardware.cooling.pump.state=available"
                + " | namespace=system; enabled=1\n"));
        assertFalse(output.contains("calling package"));
    }

    @Test
    public void keepsReadFailureDistinctFromUnsupportedControl() {
        final StringBuilder report = new StringBuilder();

        RedmagicHardwareSettings.appendDiagnostics(
                report, null, "IOException: settings unavailable");

        final String output = report.toString();
        assertTrue(output.contains(
                "hardware.settings.read=error"
                + " | IOException: settings unavailable\n"));
        assertTrue(output.contains(
                "hardware.cooling.fan.control=unavailable"
                + " | IOException: settings unavailable\n"));
        assertTrue(output.contains(
                "hardware.cooling.fan.state=unknown"
                + " | IOException: settings unavailable\n"));
    }

    @Test
    public void reportsPerSettingProviderFailureAsPartialRead() {
        final RedmagicHardwareSettings.Snapshot settings =
                RedmagicHardwareSettings.parse(
                        completeSettingsOutput().replace(
                                "setting.system.fan_state_of_manual=0|100",
                                "setting.system.fan_state_of_manual=1|"
                                        + "provider denied caller"));
        final StringBuilder report = new StringBuilder();

        RedmagicHardwareSettings.appendDiagnostics(report, settings, null);

        final String output = report.toString();
        assertTrue(output.contains("hardware.settings.read=partial"));
        assertTrue(output.contains(
                "hardware.setting.system.fan_state_of_manual=error"
                + " | settings exit 1: provider denied caller\n"));
    }

    private static String completeSettingsOutput() {
        return "setting.system.fan_state_of_manual=0|100\n"
                + "setting.system.fan_state_of_mode=0|1\n"
                + "setting.system.game_fan_off_on=0|null\n"
                + "setting.system.liquid_cooling_main_switch=0|null\n"
                + "setting.system.liquid_cooling_flow_speed_mode=0|null\n"
                + "setting.system.liquid_cooling_off_on=0|1\n"
                + "setting.global.fan_state_of_manual=0|null\n"
                + "setting.global.fan_state_of_mode=0|null\n"
                + "setting.global.game_fan_off_on=0|0\n"
                + "setting.global.liquid_cooling_main_switch=0|1\n"
                + "setting.global.liquid_cooling_flow_speed_mode=0|mid\n"
                + "setting.global.liquid_cooling_off_on=0|null\n";
    }
}
