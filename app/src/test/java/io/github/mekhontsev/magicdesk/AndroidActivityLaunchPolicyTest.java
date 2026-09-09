package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

public final class AndroidActivityLaunchPolicyTest {
    @Test
    public void publicConcreteActivityUsesDirectShellPlacement() {
        final AndroidActivityLaunchPolicy policy =
                AndroidActivityLaunchPolicy.select(
                        false, false, false, false, Intent.FLAG_ACTIVITY_NEW_TASK);

        assertEquals(AndroidActivityLaunchPolicy.Delivery.SHELL_INTENT,
                policy.delivery);
        assertFalse(policy.selectionSurface);
        assertFalse(policy.usesApplicationIdentity());
    }

    @Test
    public void chooserAndResolverUseAppOwnedPendingIntent() {
        final AndroidActivityLaunchPolicy chooser =
                AndroidActivityLaunchPolicy.select(
                        true, false, false, false, 0);
        final AndroidActivityLaunchPolicy resolver =
                AndroidActivityLaunchPolicy.select(
                        false, false, true, false, 0);

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
                        false, false, false, true, 0);

        assertEquals(AndroidActivityLaunchPolicy.Delivery.APP_PENDING_INTENT,
                policy.delivery);
        assertTrue(policy.usesApplicationIdentity());
        assertFalse(policy.selectionSurface);
    }

    @Test
    public void resultRequestRetainsActivityLifecycleOwner() {
        final AndroidActivityLaunchPolicy policy =
                AndroidActivityLaunchPolicy.select(
                        true, true, false, false, 0);

        assertEquals(AndroidActivityLaunchPolicy.Delivery.ACTIVITY_RESULT_RELAY,
                policy.delivery);
        assertTrue(policy.usesResultRelay());
        assertTrue(policy.selectionSurface);
    }

    @Test
    public void concreteFileHandlersRequireAppIdentityForReadOrWriteGrants() {
        for (final int flags : new int[] {Intent.FLAG_GRANT_READ_URI_PERMISSION,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION}) {
            final AndroidActivityLaunchPolicy policy = AndroidActivityLaunchPolicy.select(
                    false, false, false, false, flags | Intent.FLAG_ACTIVITY_NEW_TASK);
            assertEquals(AndroidActivityLaunchPolicy.Delivery.APP_PENDING_INTENT, policy.delivery);
            assertTrue(policy.usesApplicationIdentity());
            assertFalse(policy.selectionSurface);
            assertFalse(policy.usesResultRelay());
        }
    }

    @Test
    public void contentGrantsPreserveResolverAndResultOwnership() {
        final int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        final AndroidActivityLaunchPolicy resolver = AndroidActivityLaunchPolicy.select(
                false, false, true, false, grants);
        assertEquals(AndroidActivityLaunchPolicy.Delivery.APP_PENDING_INTENT, resolver.delivery);
        assertTrue(resolver.selectionSurface);
        final AndroidActivityLaunchPolicy result = AndroidActivityLaunchPolicy.select(
                false, true, false, false, grants);
        assertEquals(AndroidActivityLaunchPolicy.Delivery.ACTIVITY_RESULT_RELAY, result.delivery);
        assertFalse(result.selectionSurface);
    }
}
