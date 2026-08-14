package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NubiaWallpaperDriverTest {
    @Test
    public void normalizeThemeNameRemovesVariantSuffix() {
        assertEquals("default_theme_61",
                NubiaWallpaperDriver.normalizeThemeName(
                        " default_theme_61;variant "));
    }

    @Test
    public void safeThemeNameRejectsPathTraversal() {
        assertTrue(NubiaWallpaperDriver.isSafeThemeName(
                "default_theme_61"));
        assertFalse(NubiaWallpaperDriver.isSafeThemeName("../theme"));
        assertFalse(NubiaWallpaperDriver.isSafeThemeName("theme/name"));
    }
}
