package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RedmagicHardwareSettingsTest {
    @Test
    public void readsTypedValuesThroughOneReadOnlyCommand() throws Exception {
        final StringBuilder command = new StringBuilder();
        final RedmagicHardwareSettings.Snapshot snapshot =
                RedmagicHardwareSettings.readAll(value -> {
                    command.append(value);
                    return "setting.system.fan_state_of_manual=0|100\n"
                            + "setting.system.fan_state_of_mode=0|1\n"
                            + "setting.global.game_fan_off_on=0|0\n";
                });

        assertTrue(command.toString().contains(
                "/system/bin/settings get system fan_state_of_manual"));
        assertTrue(command.toString().contains(
                "/system/bin/settings get global liquid_cooling_off_on"));
        assertFalse(command.toString().contains("settings put"));
        assertFalse(command.toString().contains("settings delete"));
        assertEquals(
                "100",
                snapshot.value(
                        RedmagicSettingsNamespace.SYSTEM,
                        RedmagicHardwareSettings.FAN_MANUAL));
        assertEquals(
                RedmagicSettingsNamespace.SYSTEM,
                snapshot.selectNamespace(
                        RedmagicHardwareSettings.FAN_MANUAL,
                        RedmagicHardwareSettings.FAN_MODE));
        assertEquals(
                "0",
                snapshot.value(
                        RedmagicSettingsNamespace.GLOBAL,
                        RedmagicHardwareSettings.FAN_EFFECTIVE));
    }

    @Test
    public void distinguishesAbsentValueFromMissingObservation() {
        final RedmagicHardwareSettings.Snapshot snapshot =
                RedmagicHardwareSettings.parse(
                        "setting.system.fan_state_of_manual=0|null\n");

        assertTrue(snapshot.observed(
                RedmagicSettingsNamespace.SYSTEM,
                RedmagicHardwareSettings.FAN_MANUAL));
        assertEquals(
                "null",
                snapshot.value(
                        RedmagicSettingsNamespace.SYSTEM,
                        RedmagicHardwareSettings.FAN_MANUAL));
        assertFalse(snapshot.observed(
                RedmagicSettingsNamespace.GLOBAL,
                RedmagicHardwareSettings.FAN_MANUAL));
        assertNull(snapshot.selectNamespace(
                RedmagicHardwareSettings.FAN_MANUAL,
                RedmagicHardwareSettings.FAN_MODE));
    }

    @Test
    public void preservesPerSettingCommandFailure() {
        final RedmagicHardwareSettings.Snapshot snapshot =
                RedmagicHardwareSettings.parse(
                        "setting.system.fan_state_of_manual=1|permission denied\n");

        assertTrue(snapshot.observed(
                RedmagicSettingsNamespace.SYSTEM,
                RedmagicHardwareSettings.FAN_MANUAL));
        assertFalse(snapshot.readable(
                RedmagicSettingsNamespace.SYSTEM,
                RedmagicHardwareSettings.FAN_MANUAL));
        assertNull(snapshot.value(
                RedmagicSettingsNamespace.SYSTEM,
                RedmagicHardwareSettings.FAN_MANUAL));
        assertEquals(
                "settings exit 1: permission denied",
                snapshot.error(
                        RedmagicSettingsNamespace.SYSTEM,
                        RedmagicHardwareSettings.FAN_MANUAL));
    }
}
