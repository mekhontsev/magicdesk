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
        assertTrue(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.diagnosticsTarget()));
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
        assertNotNull(BuiltInDesktopAppCatalog.defaultWindowBounds(
                BuiltInDesktopAppCatalog.diagnosticsTarget()));
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
        final Set<AppReference> keys = new HashSet<>();
        keys.add(new AppProfile(0, 0).reference(
                BuiltInDesktopAppCatalog.filesTarget()));
        keys.add(new AppProfile(0, 0).reference(
                BuiltInDesktopAppCatalog.settingsTarget()));
        keys.add(new AppProfile(0, 0).reference(
                BuiltInDesktopAppCatalog.consoleTarget()));
        keys.add(new AppProfile(0, 0).reference(
                BuiltInDesktopAppCatalog.taskManagerTarget()));
        keys.add(new AppProfile(0, 0).reference(
                BuiltInDesktopAppCatalog.diagnosticsTarget()));

        assertEquals(5, keys.size());
        for (final AppReference key : keys) {
            assertEquals(key, AppReference.fromPersistentKey(key.persistentKey()));
        }
        assertEquals(
                new AppProfile(0, 0).reference(AppLaunchTarget.packageDefault("com.example")),
                new AppProfile(0, 0).reference(
                        AppLaunchTarget.packageDefault("com.example")));
    }

    @Test
    public void resolvesObservedBuiltInComponent() {
        final AppLaunchTarget console = BuiltInDesktopAppCatalog.consoleTarget();
        assertEquals(console, BuiltInDesktopAppCatalog.findComponent(
                console.activityClassName).launchTarget);
        assertNull(BuiltInDesktopAppCatalog.findComponent(
                BuildConfig.APPLICATION_ID + ".DesktopShellActivity"));
    }
}
