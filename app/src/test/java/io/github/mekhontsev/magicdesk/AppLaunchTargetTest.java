package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public final class AppLaunchTargetTest {
    @Test
    public void packageDefaultHasNoExplicitActivity() {
        final AppLaunchTarget target =
                AppLaunchTarget.packageDefault("com.example.app");

        assertEquals("com.example.app", target.packageName);
        assertEquals("", target.activityClassName);
        assertEquals("", target.action);
    }

    @Test
    public void classNameValidationRejectsShellSyntax() {
        assertTrue(AppLaunchTarget.isSafeClassName(
                "com.example.Main_Activity$Alias"));
        assertFalse(AppLaunchTarget.isSafeClassName("com.example.Main;id"));
        assertFalse(AppLaunchTarget.isSafeClassName(null));
    }

    @Test
    public void redmagicCatalogContainsUniqueExplicitTargets() {
        final Set<String> packages = new HashSet<>();
        for (final RedmagicEntryPointCatalog.EntryPoint entry
                : RedmagicEntryPointCatalog.entries()) {
            final AppLaunchTarget target = entry.launchTarget;
            assertTrue(packages.add(target.packageName));
            assertFalse(target.activityClassName.isEmpty());
            assertFalse(target.action.isEmpty());
        }
    }

    @Test
    public void invalidTargetIsRejected() {
        try {
            AppLaunchTarget.explicit(
                    "com.example;id", "com.example.Main", "action");
            fail("invalid package accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
