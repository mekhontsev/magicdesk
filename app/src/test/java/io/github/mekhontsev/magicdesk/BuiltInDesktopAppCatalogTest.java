package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BuiltInDesktopAppCatalogTest {
    @Test
    public void filesSupportsMultipleWindowsAndSettingsDoesNot() {
        assertTrue(BuiltInDesktopAppCatalog.supportsMultipleWindows(
                BuiltInDesktopAppCatalog.filesTarget()));
        assertFalse(BuiltInDesktopAppCatalog.supportsMultipleWindows(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertTrue(BuiltInDesktopAppCatalog.supportsMultipleWindows(
                AppLaunchTarget.packageDefault("com.example")));
    }

    @Test
    public void settingsDoesNotSharePackageScopedDesktopStateOrPin() {
        assertTrue(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.filesTarget()));
        assertFalse(BuiltInDesktopAppCatalog.remembersWindowState(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertTrue(BuiltInDesktopAppCatalog.isPinnable(
                BuiltInDesktopAppCatalog.filesTarget()));
        assertFalse(BuiltInDesktopAppCatalog.isPinnable(
                BuiltInDesktopAppCatalog.settingsTarget()));
    }

    @Test
    public void settingsHasCompactDefaultBoundsWhileFilesUsesAppState() {
        assertNotNull(BuiltInDesktopAppCatalog.defaultWindowBounds(
                BuiltInDesktopAppCatalog.settingsTarget()));
        assertNull(BuiltInDesktopAppCatalog.defaultWindowBounds(
                BuiltInDesktopAppCatalog.filesTarget()));
    }
}
