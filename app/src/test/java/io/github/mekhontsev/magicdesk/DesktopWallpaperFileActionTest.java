package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopWallpaperFileActionTest {
    @Test
    public void acceptsOnlyImageFiles() {
        assertTrue(DesktopWallpaperFileAction.supports(
                file("wallpaper.jpg", "image/jpeg", false)));
        assertFalse(DesktopWallpaperFileAction.supports(
                file("wallpaper.jpg", "application/octet-stream", false)));
        assertFalse(DesktopWallpaperFileAction.supports(
                file("pictures", "image/jpeg", true)));
    }

    private static ShellFileInfo file(
            final String name,
            final String mimeType,
            final boolean directory) {
        return new ShellFileInfo(
                "/tmp/" + name,
                name,
                mimeType,
                "",
                0L,
                0L,
                1L,
                2L,
                2000,
                2000,
                0100644,
                directory,
                false,
                true,
                true,
                false,
                false);
    }
}
