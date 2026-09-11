package com.termux.terminal;

/** Immutable ARGB raster. Protocol decoders never open paths supplied by a terminal client. */
public abstract class TerminalImage {
    public static final int MAX_DIMENSION = 4096;
    public static final int MAX_PIXELS = 16 * 1024 * 1024;
    public final int width;
    public final int height;

    protected TerminalImage(int width, int height) {
        checkSize(width, height);
        this.width = width;
        this.height = height;
    }

    public abstract int pixelAt(int x, int y);

    public static final Factory JAVA_RASTER = new Factory() {
        @Override public TerminalImage fromArgb(int width, int height, int[] pixels) {
            if (pixels.length != (long) width * height) throw new IllegalArgumentException("EINVAL: raster size");
            return new TerminalImage(width, height) {
                @Override public int pixelAt(int x, int y) { return pixels[y * width + x]; }
            };
        }
        @Override public TerminalImage fromPng(byte[] png) {
            throw new IllegalArgumentException("ENOTSUP: PNG decoder unavailable");
        }
    };

    public static void checkSize(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("E2BIG: image dimensions");
        }
    }

    /** Platform codec boundary; the emulator and its tests do not depend on Android Bitmap. */
    public interface Factory {
        TerminalImage fromArgb(int width, int height, int[] pixels);
        TerminalImage fromPng(byte[] png);
    }
}
