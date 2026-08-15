package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

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
        assertFalse(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertFalse(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.consoleTarget()));
        assertTrue(BuiltInDesktopAppCatalog.isPinnable(
                BuiltInDesktopAppCatalog.filesTarget()));
        assertFalse(BuiltInDesktopAppCatalog.isPinnable(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertFalse(BuiltInDesktopAppCatalog.isPinnable(
                BuiltInDesktopAppCatalog.consoleTarget()));
    }

    @Test
    public void utilityWindowsHaveDefaultBoundsWhileFilesUsesAppState() {
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
}
