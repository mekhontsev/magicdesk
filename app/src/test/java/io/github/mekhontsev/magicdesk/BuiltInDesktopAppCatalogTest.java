package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class BuiltInDesktopAppCatalogTest {
    @Test
    public void builtInsDeclareMultipleWindowPolicy() {
        assertTrue(BuiltInDesktopAppCatalog.supportsMultipleWindows(
                BuiltInDesktopAppCatalog.filesTarget()));
        assertFalse(BuiltInDesktopAppCatalog.supportsMultipleWindows(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertTrue(BuiltInDesktopAppCatalog.supportsMultipleWindows(
                BuiltInDesktopAppCatalog.consoleTarget()));
        assertTrue(BuiltInDesktopAppCatalog.supportsMultipleWindows(
                AppLaunchTarget.packageDefault("com.example")));
    }

    @Test
    public void builtInsDeclareStateAndPinPolicy() {
        assertTrue(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.filesTarget()));
        assertTrue(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertTrue(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.consoleTarget()));
        assertTrue(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.taskManagerTarget()));
        assertTrue(BuiltInDesktopAppCatalog.isPinnable(
                BuiltInDesktopAppCatalog.filesTarget()));
        assertFalse(BuiltInDesktopAppCatalog.isPinnable(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertFalse(BuiltInDesktopAppCatalog.isPinnable(
                BuiltInDesktopAppCatalog.consoleTarget()));
    }

    @Test
    public void utilityWindowsHaveFirstLaunchDefaults() {
        assertNotNull(BuiltInDesktopAppCatalog.defaultWindowBounds(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertNotNull(BuiltInDesktopAppCatalog.defaultWindowBounds(
                BuiltInDesktopAppCatalog.consoleTarget()));
        assertNull(BuiltInDesktopAppCatalog.defaultWindowBounds(
                BuiltInDesktopAppCatalog.filesTarget()));
    }

    @Test
    public void resolvesConsoleTaskByActivityInsteadOfPackageFallback() {
        final TaskRepository.TaskEntry task = new TaskRepository.TaskEntry(
                1,
                2,
                3,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.CommandConsoleActivity",
                BuildConfig.APPLICATION_ID + "/.CommandConsoleActivity",
                "freeform",
                new Rect(0, 0, 400, 300),
                false,
                true,
                true);

        assertEquals(
                BuiltInDesktopAppCatalog.consoleTarget(),
                BuiltInDesktopAppCatalog.find(task).launchTarget);
    }

    @Test
    public void builtInsHaveIndependentWindowStateKeys() {
        final Set<String> keys = new HashSet<>();
        keys.add(BuiltInDesktopAppCatalog.appIdentityKey(
                BuiltInDesktopAppCatalog.filesTarget()));
        keys.add(BuiltInDesktopAppCatalog.appIdentityKey(
                BuiltInDesktopAppCatalog.settingsTarget()));
        keys.add(BuiltInDesktopAppCatalog.appIdentityKey(
                BuiltInDesktopAppCatalog.consoleTarget()));
        keys.add(BuiltInDesktopAppCatalog.appIdentityKey(
                BuiltInDesktopAppCatalog.taskManagerTarget()));

        assertEquals(4, keys.size());
        for (final String key : keys) {
            assertTrue(BuiltInDesktopAppCatalog.isAppIdentityKey(key));
        }
        assertEquals(
                "com.example",
                BuiltInDesktopAppCatalog.appIdentityKey(
                        AppLaunchTarget.packageDefault("com.example")));
    }

    @Test
    public void resolvesObservedBuiltInComponentToItsStateKey() {
        final AppLaunchTarget console =
                BuiltInDesktopAppCatalog.consoleTarget();

        assertEquals(
                BuiltInDesktopAppCatalog.appIdentityKey(console),
                BuiltInDesktopAppCatalog.appIdentityKey(
                        BuildConfig.APPLICATION_ID,
                        BuildConfig.APPLICATION_ID
                                + "/.CommandConsoleActivity"));
        assertNull(BuiltInDesktopAppCatalog.appIdentityKey(
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "/.DesktopShellActivity"));
    }
}
