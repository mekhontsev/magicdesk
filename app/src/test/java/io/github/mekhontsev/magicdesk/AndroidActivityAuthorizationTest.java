package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AndroidActivityAuthorizationTest {
    @Test
    public void allowsExportedEnabledActivityWithGrantedPermission() {
        final AndroidActivityAuthorization authorization =
                AndroidActivityAuthorization.evaluate(
                        true, true, "android.permission.EXAMPLE", true);

        assertTrue(authorization.allowed());
        assertTrue(authorization.requiresAppIdentity());
        assertEquals("allowed", authorization.decisionName());
    }

    @Test
    public void reportsTheFirstDeniedBoundary() {
        assertDecision(
                AndroidActivityAuthorization.COMPONENT_DISABLED,
                "component-disabled",
                AndroidActivityAuthorization.evaluate(
                        false, false, "permission", false));
        assertDecision(
                AndroidActivityAuthorization.COMPONENT_NOT_EXPORTED,
                "component-not-exported",
                AndroidActivityAuthorization.evaluate(
                        false, true, "permission", false));
        assertDecision(
                AndroidActivityAuthorization.REQUIRED_PERMISSION_DENIED,
                "required-permission-denied",
                AndroidActivityAuthorization.evaluate(
                        true, true, "permission", false));
    }

    @Test
    public void ownNonExportedActivityUsesAppIdentity() {
        final AndroidActivityAuthorization authorization =
                AndroidActivityAuthorization.evaluate(
                        false, true, "", true, true);

        assertTrue(authorization.allowed());
        assertTrue(authorization.requiresAppIdentity());
        assertTrue(authorization.samePackage);
    }

    @Test
    public void ownProtectedActivityDoesNotRequireItsOwnComponentPermission() {
        for (final boolean exported : new boolean[] {true, false}) {
            final var authorization = AndroidActivityAuthorization.evaluate(
                    exported, true, "android.permission.MANAGE_ACTIVITY_TASKS", false, true);
            assertTrue(authorization.allowed());
            assertFalse(authorization.permissionGranted);
            assertTrue(authorization.requiresAppIdentity());
            assertEquals(AndroidActivityLaunchPolicy.Delivery.APP_PENDING_INTENT,
                    AndroidActivityLaunchPolicy.select(false, false, false,
                            authorization.requiresAppIdentity(), 0).delivery);
        }
    }

    @Test
    public void ownershipDoesNotBypassDisabledStateOrAuthorizeAForeignProtectedActivity() {
        assertDecision(AndroidActivityAuthorization.COMPONENT_DISABLED, "component-disabled",
                AndroidActivityAuthorization.evaluate(
                        true, false, "android.permission.MANAGE_ACTIVITY_TASKS", false, true));
        assertDecision(AndroidActivityAuthorization.REQUIRED_PERMISSION_DENIED,
                "required-permission-denied", AndroidActivityAuthorization.evaluate(
                        true, true, "android.permission.MANAGE_ACTIVITY_TASKS", false, false));
        assertDecision(AndroidActivityAuthorization.COMPONENT_NOT_EXPORTED,
                "component-not-exported", AndroidActivityAuthorization.evaluate(
                        false, true, "android.permission.MANAGE_ACTIVITY_TASKS", false, false));
    }

    @Test
    public void publicActivityWithoutPermissionCanUseShellPlacement() {
        final AndroidActivityAuthorization authorization =
                AndroidActivityAuthorization.evaluate(
                        true, true, "", true);

        assertTrue(authorization.allowed());
        assertFalse(authorization.requiresAppIdentity());
    }

    private static void assertDecision(
            final int expectedDecision,
            final String expectedName,
            final AndroidActivityAuthorization authorization) {
        assertFalse(authorization.allowed());
        assertEquals(expectedDecision, authorization.decision);
        assertEquals(expectedName, authorization.decisionName());
    }
}
