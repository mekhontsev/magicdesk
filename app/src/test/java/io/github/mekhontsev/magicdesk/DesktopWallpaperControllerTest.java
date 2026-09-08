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
    public void absentCustomFileRestoresBundledWallpaperAndClearsCache() throws Exception {
        verifySourceSelection("""
                Files.writeString(cache.toPath(), "valid");
                WallpaperResult result = fixture.loadWallpaper(1920, 1080, () -> false);
                check(result.bitmap.equals("bundled"), "no custom file selects the bundled image");
                check(!result.custom && !result.fallback, "bundled artwork is a normal source");
                check(!cache.exists(), "removed override cannot return through the cache");
                check(diagnostics == 0, "default selection is not a compatibility failure");
                """);
    }

    @Test
    public void customFileTakesPriorityOverCacheAndBundledWallpaper() throws Exception {
        verifySourceSelection("""
                customAvailable = true;
                Files.writeString(cache.toPath(), "valid");
                WallpaperResult result = fixture.loadWallpaper(2560, 1080, () -> false);
                check(result.bitmap.equals("custom") && result.custom, "custom image takes priority");
                check(defaultLoads == 0 && diagnostics == 0, "no default load or spurious warning");
                """);
    }

    @Test
    public void failedCustomReadUsesLastValidCacheOrBundledWallpaper() throws Exception {
        verifySourceSelection("""
                customReadFails = true;
                Files.writeString(cache.toPath(), "valid");
                WallpaperResult result = fixture.loadWallpaper(1920, 1080, () -> false);
                check(result.bitmap.equals("cached") && result.custom, "last valid image is retained");
                Files.writeString(cache.toPath(), "broken");
                result = fixture.loadWallpaper(1920, 1080, () -> false);
                check(result.bitmap.equals("bundled"), "invalid cache falls back to bundled artwork");
                check(!cache.exists(), "invalid cache is discarded");
                check(diagnostics == 2, "custom failures remain observable");
                """);
    }

    @Test
    public void unavailableShellDoesNotPreventBundledOrCachedWallpaper() throws Exception {
        verifySourceSelection("""
                ShellAccess.ready = false;
                WallpaperResult result = fixture.loadWallpaper(1216, 2688, () -> false);
                check(result.bitmap.equals("bundled"), "bundled image needs no shell service");
                Files.writeString(cache.toPath(), "valid");
                result = fixture.loadWallpaper(1216, 2688, () -> false);
                check(result.bitmap.equals("cached"), "cached custom selection remains usable");
                check(copyAttempts == 0 && diagnostics == 0, "no privileged read or warning");
                """);
    }

    @Test
    public void cancelledCustomReadDoesNotLoadFallbackOrReportFailure() throws Exception {
        verifySourceSelection("""
                try {
                    fixture.loadWallpaper(1920, 1080, () -> true);
                    throw new AssertionError("cancelled load returned a frame");
                } catch (InterruptedIOException expected) { }
                check(defaultLoads == 0 && diagnostics == 0, "cancellation is not a source failure");
                """);
    }

    private static void verifySourceSelection(final String assertions) throws Exception {
        RuntimeSourceFixture.verify("""
                interface BooleanSupplier extends java.util.function.BooleanSupplier { }
                static final String TAG = "test";
                static File directory, pending;
                static boolean customAvailable, customReadFails;
                static int copyAttempts, defaultLoads, diagnostics;
                static final class Context { File getCacheDir() { return directory; } }
                final Context mContext = new Context();
                static final class ShellAccess {
                    static boolean ready = true;
                    static boolean isReady() { return ready; }
                }
                static final class Log { static void w(String tag, String text, Throwable error) { } }
                static final class CompatibilityDiagnostics {
                    static void record(String code, String text, String detail, Throwable error) {
                        diagnostics++;
                    }
                }
                static final class ContentStreamCopy {
                    static void checkCancelled(BooleanSupplier cancelled) throws IOException {
                        if (cancelled.getAsBoolean()) throw new InterruptedIOException("cancelled");
                    }
                }
                static final class WallpaperResult {
                    final String bitmap;
                    final boolean custom, fallback;
                    WallpaperResult(String b, boolean c, boolean f) { bitmap=b; custom=c; fallback=f; }
                }
                static File createPendingFile(File cache) throws IOException {
                    pending = File.createTempFile("wallpaper-", ".pending", cache);
                    return pending;
                }
                static boolean copyCustomWallpaper(File file, BooleanSupplier cancelled) throws IOException {
                    ContentStreamCopy.checkCancelled(cancelled);
                    copyAttempts++;
                    if (customReadFails) throw new IOException("unreadable");
                    return customAvailable;
                }
                WallpaperResult decodeAndCache(File source, File cache, int w, int h,
                        BooleanSupplier cancelled) { return new WallpaperResult("custom", true, false); }
                String decodeWallpaper(File file, int w, int h) throws IOException {
                    if (!Files.readString(file.toPath()).equals("valid")) throw new IOException("corrupt");
                    return "cached";
                }
                WallpaperResult defaultWallpaper(int w, int h) {
                    defaultLoads++;
                    return new WallpaperResult("bundled", false, false);
                }
                """ + RuntimeSourceFixture.methods("DesktopWallpaperController",
                        "loadWallpaper", "cachedOrDefault", "usefulMessage")
                + """
                public static void verify() throws Exception {
                    directory = Files.createTempDirectory("wallpaper-selection-").toFile();
                    File cache = new File(directory, "desktop-custom-wallpaper");
                    Fixture fixture = new Fixture();
                    try {
                """ + assertions + """
                        check(pending == null || !pending.exists(), "temporary transfer is cleaned up");
                    } finally {
                        try (var files = Files.walk(directory.toPath())) {
                            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                                Files.delete(path);
                            }
                        }
                    }
                }
                """);
    }

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
