package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class RedmagicSettingsNamespaceTest {
    private static final String FIRST = "fan_state_of_manual";
    private static final String SECOND = "fan_state_of_mode";

    @Test
    public void selectsSystemWhenItsSettingGroupIsMoreComplete() {
        final String output =
                "setting.system.fan_state_of_manual=0|100\n"
                + "setting.system.fan_state_of_mode=0|1\n"
                + "setting.global.fan_state_of_manual=0|-4\n"
                + "setting.global.fan_state_of_mode=0|null\n";

        assertEquals(
                RedmagicSettingsNamespace.SYSTEM,
                select(output));
    }

    @Test
    public void selectsGlobalForFirmwareThatStoresTheGroupThere() {
        final String output =
                "setting.system.fan_state_of_manual=0|null\n"
                + "setting.system.fan_state_of_mode=0|null\n"
                + "setting.global.fan_state_of_manual=0|1\n"
                + "setting.global.fan_state_of_mode=0|0\n";

        assertEquals(
                RedmagicSettingsNamespace.GLOBAL,
                select(output));
    }

    @Test
    public void leavesAnUnknownGroupUnsupported() {
        final String output =
                "setting.system.fan_state_of_manual=0|null\n"
                + "setting.system.fan_state_of_mode=0|null\n"
                + "setting.global.fan_state_of_manual=0|null\n"
                + "setting.global.fan_state_of_mode=0|null\n";

        assertNull(select(output));
    }

    @Test
    public void doesNotGuessWhenBothNamespacesLookSupported() {
        final String output =
                "setting.system.fan_state_of_manual=0|1\n"
                + "setting.system.fan_state_of_mode=0|0\n"
                + "setting.global.fan_state_of_manual=0|1\n"
                + "setting.global.fan_state_of_mode=0|0\n";

        assertNull(select(output));
    }

    private static RedmagicSettingsNamespace select(final String output) {
        return RedmagicHardwareSettings.parse(output)
                .selectNamespace(FIRST, SECOND);
    }
}
