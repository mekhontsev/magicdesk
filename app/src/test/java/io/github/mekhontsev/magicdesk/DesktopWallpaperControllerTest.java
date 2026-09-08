package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class DesktopWallpaperControllerTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void displaySizedSourceDoesNotRetainItsDensity() throws Exception {
        verifyDisplayFrame("""
                Bitmap source = new Bitmap(1920, 1080);
                source.setDensity(520);
                WallpaperResult original = new WallpaperResult(source, true, false);
                WallpaperResult result = renderDisplayFrame(original, 1920, 1080);
                check(result == original, "exact-size source must not be copied");
                check(!source.recycled, "reused bitmap remains owned by the view");
                check(result.bitmap.density == Bitmap.DENSITY_NONE,
                        "pixel-sized source must not be rescaled by ImageView density");
                """);
    }

    @Test
    public void croppedFramesArePixelSizedRegardlessOfProcessDensity() throws Exception {
        verifyDisplayFrame("""
                int[][] displays = {{1920,1080}, {2560,1080}, {1216,2688}};
                for (int density : new int[] {160, 200, 520}) {
                    Bitmap.defaultDensity = density;
                    for (int[] size : displays) {
                        Bitmap source = new Bitmap(800, 600);
                        WallpaperResult result = renderDisplayFrame(
                                new WallpaperResult(source, false, true), size[0], size[1]);
                        check(result.bitmap.width == size[0] && result.bitmap.height == size[1],
                                "frame retains requested pixel dimensions");
                        check(result.bitmap.density == Bitmap.DENSITY_NONE,
                                "new frame must not inherit process density " + density);
                        check(source.recycled, "replaced source must be recycled");
                        check(!result.custom && result.fallback, "source provenance is preserved");
                    }
                }
                """);
    }

    private static void verifyDisplayFrame(final String assertions) throws Exception {
        RuntimeSourceFixture.verify("""
                static final class Bitmap {
                    static final int DENSITY_NONE = 0;
                    static int defaultDensity = 520;
                    enum Config { ARGB_8888 }
                    final int width, height;
                    int density = defaultDensity;
                    boolean recycled;
                    Bitmap(int w, int h) { width = w; height = h; }
                    static Bitmap createBitmap(int w, int h, Config config) { return new Bitmap(w,h); }
                    int getWidth() { return width; }
                    int getHeight() { return height; }
                    void setDensity(int value) { density = value; }
                    void recycle() { recycled = true; }
                }
                static final class WallpaperResult {
                    final Bitmap bitmap;
                    final boolean custom, fallback;
                    WallpaperResult(Bitmap b, boolean c, boolean f) { bitmap=b; custom=c; fallback=f; }
                }
                static final class RectF { RectF(float l, float t, float r, float b) { } }
                static final class Paint {
                    static final int FILTER_BITMAP_FLAG=2, DITHER_FLAG=4;
                    Paint(int flags) { }
                }
                static final class Canvas {
                    Canvas(Bitmap bitmap) { }
                    void drawBitmap(Bitmap source, Object crop, RectF destination, Paint paint) { }
                }
                """ + RuntimeSourceFixture.methods("DesktopWallpaperController", "renderDisplayFrame")
                + "public static void verify() {" + assertions + "}");
    }

    @Test
    public void panoramicAndPortraitImagesHaveABoundedDecodedAllocation() {
        assertEquals(4, DesktopWallpaperController.calculateSampleSize(
                100_000, 1_000, 1920, 1080));
        assertEquals(4, DesktopWallpaperController.calculateSampleSize(
                1_000, 100_000, 1080, 1920));
    }

    @Test
    public void ordinaryWallpapersKeepExistingResolutionSampling() {
        assertEquals(1, DesktopWallpaperController.calculateSampleSize(
                1920, 1080, 1920, 1080));
        assertEquals(4, DesktopWallpaperController.calculateSampleSize(
                7680, 4320, 1920, 1080));
        assertEquals(1, DesktopWallpaperController.calculateSampleSize(
                640, 480, 1920, 1080));
    }

    @Test
    public void samplingBoundsRoundedDimensionsWithoutIntegerOverflow() {
        final int[][] dimensions = {
                {Integer.MAX_VALUE, Integer.MAX_VALUE},
                {Integer.MAX_VALUE, 1},
                {1, Integer.MAX_VALUE},
                {100_001, 1_001},
                {4097, 4097}
        };
        for (final int[] size : dimensions) {
            final int sample = DesktopWallpaperController.calculateSampleSize(
                    size[0], size[1], Integer.MAX_VALUE, Integer.MAX_VALUE);
            assertTrue(sample > 0 && (sample & (sample - 1)) == 0);
            final long width = (size[0] + sample - 1L) / sample;
            final long height = (size[1] + sample - 1L) / sample;
            assertTrue(width * height <= 16L * 1024 * 1024);
        }
    }

    @Test
    public void samplingRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () ->
                DesktopWallpaperController.calculateSampleSize(0, 100, 100, 100));
        assertThrows(IllegalArgumentException.class, () ->
                DesktopWallpaperController.calculateSampleSize(100, 100, 0, 100));
    }

    @Test
    public void overlappingLoadsOwnIndependentTemporaryFiles() throws IOException {
        final File directory = temporary.newFolder();
        final File first = DesktopWallpaperController.createPendingFile(directory);
        Files.writeString(first.toPath(), "first image");
        final File second = DesktopWallpaperController.createPendingFile(directory);
        Files.writeString(second.toPath(), "second image");

        assertNotEquals(first, second);
        assertEquals("first image", Files.readString(first.toPath()));
        assertEquals("second image", Files.readString(second.toPath()));
        Files.delete(first.toPath());
        assertEquals("second image", Files.readString(second.toPath()));
    }

    @Test
    public void pendingFileIsReservedAndDoesNotReplaceUnownedFiles() throws IOException {
        final File directory = temporary.newFolder();
        final File unrelated = new File(directory, "desktop-wallpaper.pending");
        Files.writeString(unrelated.toPath(), "not ours");
        final File pending = DesktopWallpaperController.createPendingFile(directory);

        assertNotEquals(unrelated, pending);
        assertTrue(pending.isFile());
        assertEquals(directory.getCanonicalFile(), pending.getParentFile().getCanonicalFile());
        assertEquals(0, pending.length());
        assertEquals("not ours", Files.readString(unrelated.toPath()));
    }
}
