package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DeviceSetupOverlayPolicyTest {
    @Test
    public void overlayProvisioningTargetsOnlyMagicDesk() {
        assertEquals(
                "/system/bin/cmd appops set io.github.mekhontsev.magicdesk "
                        + "SYSTEM_ALERT_WINDOW allow",
                DeviceSetupManager.overlayPermissionCommand());
    }
}
