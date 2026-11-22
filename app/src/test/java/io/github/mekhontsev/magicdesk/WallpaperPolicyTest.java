package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public final class WallpaperPolicyTest {
    @Test public void eachIndependentConstraintCanStopMotion() {
        assertTrue(WallpaperPolicy.animate(true, false, true, false, true, true));
        assertFalse(WallpaperPolicy.animate(false, false, true, false, true, true));
        assertFalse(WallpaperPolicy.animate(true, true, true, false, true, true));
        assertFalse(WallpaperPolicy.animate(true, false, false, false, true, true));
        assertFalse(WallpaperPolicy.animate(true, false, true, true, true, true));
        assertFalse(WallpaperPolicy.animate(true, false, true, false, false, true));
        assertFalse(WallpaperPolicy.animate(true, false, true, false, true, false));
    }

    @Test public void videoAdmissionBoundsDimensionsDurationAndRate() throws Exception {
        WallpaperPolicy.video(1920, 1080, 300_000, 60);
        WallpaperPolicy.video(1080, 1920, 1, 0);
        assertThrows(IOException.class, () -> WallpaperPolicy.video(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 30));
        assertThrows(IOException.class, () -> WallpaperPolicy.video(3840, 2160, 1, 30));
        assertThrows(IOException.class, () -> WallpaperPolicy.video(1920, 1080, 0, 30));
        assertThrows(IOException.class, () -> WallpaperPolicy.video(1920, 1080, 300_001, 30));
        assertThrows(IOException.class, () -> WallpaperPolicy.video(1920, 1080, 1, 61));
        assertThrows(IOException.class, () -> WallpaperPolicy.video(1920, 1080, 1, Float.NaN));
    }

    @Test public void cropUsesPhysicalOutputNotInsetsOrUiDensity() {
        var exact = WallpaperPolicy.crop(ShellResources.WallpaperFit.COVER, 1920, 1080, 1920, 1080);
        assertEquals(1, exact.scale(), 0); assertEquals(0, exact.x(), 0); assertEquals(0, exact.y(), 0);
        for (int[] output : new int[][] {{1920,1080}, {2560,1080}, {1216,2688}}) {
            var crop = WallpaperPolicy.crop(ShellResources.WallpaperFit.COVER, 800, 600, output[0], output[1]);
            assertTrue(800 * crop.scale() >= output[0] && 600 * crop.scale() >= output[1]);
            assertEquals(output[0] / 2f, crop.x() + 400 * crop.scale(), .001f);
            assertEquals(output[1] / 2f, crop.y() + 300 * crop.scale(), .001f);
        }
        assertThrows(IllegalArgumentException.class, () -> WallpaperPolicy.crop(ShellResources.WallpaperFit.COVER, 0, 0, 1, 1));
    }

    @Test public void containFitsWholeSourceAndCentersIt() {
        for (int[] source : new int[][] {{1920,1080}, {1080,1920}, {800,600}}) {
            for (int[] output : new int[][] {{1920,1080}, {2560,1080}, {1216,2688}}) {
                var crop = WallpaperPolicy.crop(ShellResources.WallpaperFit.CONTAIN, source[0], source[1], output[0], output[1]);
                assertTrue(source[0] * crop.scale() <= output[0] + .001f && source[1] * crop.scale() <= output[1] + .001f);
                assertEquals(output[0] / 2f, crop.x() + source[0] * crop.scale() / 2, .001f);
                assertEquals(output[1] / 2f, crop.y() + source[1] * crop.scale() / 2, .001f);
            }
        }
    }
}
