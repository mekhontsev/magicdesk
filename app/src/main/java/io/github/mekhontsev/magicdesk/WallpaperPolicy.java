package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Shared admission and playback policy, independent of codecs and Desktop focus. */
final class WallpaperPolicy {
    private static final long MAX_DECODED_PIXELS = 16L * 1024 * 1024;
    static final long MAX_MOTION_PIXELS = 4L * 1024 * 1024;
    static final long MAX_VIDEO_MILLIS = 300_000;

    static void video(int width, int height, long durationMillis, float frameRate) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_MOTION_PIXELS
                || durationMillis <= 0 || durationMillis > MAX_VIDEO_MILLIS
                || !Float.isFinite(frameRate) || frameRate < 0 || frameRate > 60) {
            throw new IOException("Wallpaper video exceeds 4 megapixels, 60 fps or 5 minutes");
        }
    }

    static boolean animate(boolean enabled, boolean reduced, boolean systemAnimations,
            boolean powerSave, boolean visible, boolean displayOn) {
        return enabled && !reduced && systemAnimations && !powerSave && visible && displayOn;
    }

    static int calculateSampleSize(final int sourceWidth, final int sourceHeight,
            final int targetWidth, final int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0
                || targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("wallpaper dimensions must be positive");
        }
        int sampleSize = 1;
        while (sourceWidth / (sampleSize * 2L) >= targetWidth
                && sourceHeight / (sampleSize * 2L) >= targetHeight) {
            sampleSize *= 2;
        }
        // Encoded size and the short image edge do not bound decoded memory.
        while (((sourceWidth + sampleSize - 1L) / sampleSize)
                * ((sourceHeight + sampleSize - 1L) / sampleSize)
                > MAX_DECODED_PIXELS) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    record Crop(float scale, float x, float y) {}
    static Crop crop(ShellResources.WallpaperFit fit, int sourceWidth, int sourceHeight,
            int displayWidth, int displayHeight) {
        if (sourceWidth < 1 || sourceHeight < 1 || displayWidth < 1 || displayHeight < 1) {
            throw new IllegalArgumentException("Invalid wallpaper dimensions");
        }
        float scale = fit == ShellResources.WallpaperFit.CONTAIN
                ? Math.min(displayWidth / (float) sourceWidth, displayHeight / (float) sourceHeight)
                : Math.max(displayWidth / (float) sourceWidth, displayHeight / (float) sourceHeight);
        return new Crop(scale, (displayWidth - sourceWidth * scale) / 2,
                (displayHeight - sourceHeight * scale) / 2);
    }
}
