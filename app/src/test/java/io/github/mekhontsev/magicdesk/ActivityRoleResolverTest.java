package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ActivityRoleResolverTest {
    @Test
    public void autoLaunchOnPrimaryDisplayOpensPhoneControl() {
        assertTrue(ActivityRoleResolver.opensPhoneControl(
                SessionProfile.DisplayTarget.AUTO, 0));
    }

    @Test
    public void autoLaunchOnExternalDisplayOpensDesktop() {
        assertFalse(ActivityRoleResolver.opensPhoneControl(
                SessionProfile.DisplayTarget.AUTO, 17));
    }

    @Test
    public void explicitDisplayTargetsAlwaysOpenDesktop() {
        assertFalse(ActivityRoleResolver.opensPhoneControl(
                SessionProfile.DisplayTarget.PRIMARY, 0));
        assertFalse(ActivityRoleResolver.opensPhoneControl(
                SessionProfile.DisplayTarget.CURRENT, 0));
        assertFalse(ActivityRoleResolver.opensPhoneControl(
                SessionProfile.DisplayTarget.EXTERNAL, 0));
    }
}
