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
