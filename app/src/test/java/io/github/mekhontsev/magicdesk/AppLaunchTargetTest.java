package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    public void invalidTargetIsRejected() {
        try {
            AppLaunchTarget.explicit(
                    "com.example;id", "com.example.Main", "action");
            fail("invalid package accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void stableIdentityIncludesExplicitEntryPoint() {
        final AppLaunchTarget first = AppLaunchTarget.explicit(
                "com.example.app", "com.example.First", "example.OPEN");
        final AppLaunchTarget same = AppLaunchTarget.explicit(
                "com.example.app", "com.example.First", "example.OPEN");
        final AppLaunchTarget second = AppLaunchTarget.explicit(
                "com.example.app", "com.example.Second", "example.OPEN");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(second));
        assertFalse(first.stableKey().equals(second.stableKey()));
    }

    @Test
    public void explicitTargetMatchesOnlyItsTaskComponent() {
        final AppLaunchTarget target = AppLaunchTarget.explicit(
                "io.example.app",
                "io.example.app.FilesActivity",
                "android.intent.action.MAIN");
        final TaskRepository.TaskEntry matching = new TaskRepository.TaskEntry(
                1, 2, 3,
                "io.example.app",
                "io.example.app/.FilesActivity",
                "io.example.app/io.example.app.FilesActivity",
                "freeform",
                null,
                false,
                true,
                true);
        final TaskRepository.TaskEntry other = new TaskRepository.TaskEntry(
                1, 3, 3,
                "io.example.app",
                "io.example.app/.MainActivity",
                "io.example.app/.MainActivity",
                "freeform",
                null,
                false,
                true,
                true);

        assertTrue(target.matchesTask(matching));
        assertFalse(target.matchesTask(other));
        assertTrue(target.matchesTask(
                "io.example.app",
                "io.example.app/.FilesActivity",
                "io.example.app/.FilesActivity"));
        assertFalse(target.matchesTask(
                "io.example.app",
                "io.example.app/.MainActivity",
                "io.example.app/.MainActivity"));
    }
}
