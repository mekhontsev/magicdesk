package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class RedmagicSettingsNamespaceTest {
    private static final String FIRST = "fan_state_of_manual";
    private static final String SECOND = "fan_state_of_mode";

    @Test
    public void selectsSystemWhenItsSettingGroupIsMoreComplete() {
        final String output =
                "setting.system.fan_state_of_manual=100\n"
                + "setting.system.fan_state_of_mode=1\n"
                + "setting.global.fan_state_of_manual=-4\n"
                + "setting.global.fan_state_of_mode=null\n";

        assertEquals(
                RedmagicSettingsNamespace.SYSTEM,
                RedmagicSettingsNamespace.select(output, FIRST, SECOND));
    }

    @Test
    public void selectsGlobalForFirmwareThatStoresTheGroupThere() {
        final String output =
                "setting.system.fan_state_of_manual=null\n"
                + "setting.system.fan_state_of_mode=null\n"
                + "setting.global.fan_state_of_manual=1\n"
                + "setting.global.fan_state_of_mode=0\n";

        assertEquals(
                RedmagicSettingsNamespace.GLOBAL,
                RedmagicSettingsNamespace.select(output, FIRST, SECOND));
    }

    @Test
    public void leavesAnUnknownGroupUnsupported() {
        final String output =
                "setting.system.fan_state_of_manual=null\n"
                + "setting.system.fan_state_of_mode=null\n"
                + "setting.global.fan_state_of_manual=null\n"
                + "setting.global.fan_state_of_mode=null\n";

        assertNull(RedmagicSettingsNamespace.select(output, FIRST, SECOND));
    }

    @Test
    public void doesNotGuessWhenBothNamespacesLookSupported() {
        final String output =
                "setting.system.fan_state_of_manual=1\n"
                + "setting.system.fan_state_of_mode=0\n"
                + "setting.global.fan_state_of_manual=1\n"
                + "setting.global.fan_state_of_mode=0\n";

        assertNull(RedmagicSettingsNamespace.select(output, FIRST, SECOND));
    }
}
