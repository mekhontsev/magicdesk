package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ActivityRoleResolverTest {
    @Test
    public void everyLaunchOnPrimaryDisplayOpensPhoneControl() {
        assertTrue(ActivityRoleResolver.opensPhoneControl(0));
    }

    @Test
    public void everyLaunchOnExternalDisplayOpensDesktop() {
        assertFalse(ActivityRoleResolver.opensPhoneControl(17));
    }
}
