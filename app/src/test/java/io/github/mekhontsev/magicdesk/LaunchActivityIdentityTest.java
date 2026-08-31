package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LaunchActivityIdentityTest {
    private static final String PACKAGE_NAME = "com.example.app";
    private static final String REQUESTED_CLASS =
            "com.example.app.LauncherAlias";
    private static final String RESOLVED_CLASS =
            "com.example.app.RealActivity";

    @Test
    public void matchesRequestedComponentAndAliasTarget() {
        assertTrue(matches(PACKAGE_NAME, REQUESTED_CLASS));
        assertTrue(matches(PACKAGE_NAME, RESOLVED_CLASS));
    }

    @Test
    public void rejectsUnrelatedActivityFromSamePackage() {
        assertFalse(matches(
                PACKAGE_NAME, "com.example.app.OtherActivity"));
    }

    @Test
    public void rejectsComponentFromAnotherPackage() {
        assertFalse(matches(
                "com.example.other", "com.example.other.RealActivity"));
    }

    @Test
    public void packageScopedIdentityAcceptsRedirectWithinPublisher() {
        assertTrue(LaunchActivityIdentity.matchesPackage(
                PACKAGE_NAME, PACKAGE_NAME));
        assertFalse(LaunchActivityIdentity.matchesPackage(
                PACKAGE_NAME, "com.example.other"));
    }

    private static boolean matches(
            final String observedPackageName,
            final String observedClassName) {
        return LaunchActivityIdentity.matches(
                PACKAGE_NAME,
                REQUESTED_CLASS,
                RESOLVED_CLASS,
                observedPackageName,
                observedClassName);
    }
}
