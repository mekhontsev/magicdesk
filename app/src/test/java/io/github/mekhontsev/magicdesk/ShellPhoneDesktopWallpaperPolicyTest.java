package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellPhoneDesktopWallpaperPolicyTest {
    private static final String SYSTEM_DESKTOP_WALLPAPER =
            "com.android.systemui/"
                    + "com.android.wm.shell.desktopmode.DesktopWallpaperActivity";

    @Test
    public void blocksOnlySystemWallpaperWhileEnabled() {
        assertTrue(ShellPhoneDesktopWallpaperPolicy.shouldBlock(
                true, SYSTEM_DESKTOP_WALLPAPER));
        assertFalse(ShellPhoneDesktopWallpaperPolicy.shouldBlock(
                false, SYSTEM_DESKTOP_WALLPAPER));
        assertFalse(ShellPhoneDesktopWallpaperPolicy.shouldBlock(
                true,
                "com.android.systemui/"
                        + "com.android.systemui.recents.RecentsActivity"));
        assertFalse(ShellPhoneDesktopWallpaperPolicy.shouldBlock(true, null));
    }
}
