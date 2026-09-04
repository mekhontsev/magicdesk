package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AndroidActivityLaunchPolicyTest {
    @Test
    public void publicConcreteActivityUsesDirectShellPlacement() {
        final AndroidActivityLaunchPolicy policy =
                AndroidActivityLaunchPolicy.select(
                        false, false, false, false);

        assertEquals(AndroidActivityLaunchPolicy.Delivery.SHELL_INTENT,
                policy.delivery);
        assertFalse(policy.selectionSurface);
        assertFalse(policy.usesApplicationIdentity());
    }

    @Test
    public void chooserAndResolverUseAppOwnedPendingIntent() {
        final AndroidActivityLaunchPolicy chooser =
                AndroidActivityLaunchPolicy.select(
                        true, false, false, false);
        final AndroidActivityLaunchPolicy resolver =
                AndroidActivityLaunchPolicy.select(
                        false, false, true, false);

        assertEquals(AndroidActivityLaunchPolicy.Delivery.APP_PENDING_INTENT,
                chooser.delivery);
        assertEquals(AndroidActivityLaunchPolicy.Delivery.APP_PENDING_INTENT,
                resolver.delivery);
        assertTrue(chooser.selectionSurface);
        assertTrue(resolver.selectionSurface);
    }

    @Test
    public void appPermissionDoesNotGiveShellCallerIdentity() {
        final AndroidActivityLaunchPolicy policy =
                AndroidActivityLaunchPolicy.select(
                        false, false, false, true);

        assertEquals(AndroidActivityLaunchPolicy.Delivery.APP_PENDING_INTENT,
                policy.delivery);
        assertTrue(policy.usesApplicationIdentity());
        assertFalse(policy.selectionSurface);
    }

    @Test
    public void resultRequestRetainsActivityLifecycleOwner() {
        final AndroidActivityLaunchPolicy policy =
                AndroidActivityLaunchPolicy.select(
                        true, true, false, false);

        assertEquals(AndroidActivityLaunchPolicy.Delivery.ACTIVITY_RESULT_RELAY,
                policy.delivery);
        assertTrue(policy.usesResultRelay());
        assertTrue(policy.selectionSurface);
    }
}
