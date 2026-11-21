package io.github.mekhontsev.magicdesk.hosted;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import java.io.IOException;

/** A bounded graphics consumer, with no Android window or display. Pixels are read only on capture. */
public final class HostedImageOutput implements AutoCloseable {
    public record Snapshot(Bitmap bitmap, long sequence, long receivedUptimeMillis) { }
    private final ImageReader reader;
    private Image latest;
    private long sequence, received;
    private boolean closed;
    private String failure = "";
    private final Runnable readiness;

    public HostedImageOutput(int width, int height, Handler callbacks, Runnable readiness) {
        validateSize(width, height);
        this.readiness = readiness;
        // One retained image plus two acquisition slots lets acquireLatestImage discard queued frames.
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::receive, callbacks);
    }

    public static void validateSize(int width, int height) {
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16_777_216)
            throw new IllegalArgumentException("Output size must be 1..8192 per axis and at most 16 megapixels");
    }

    public Surface surface() { return reader.getSurface(); }
    public int width() { return reader.getWidth(); }
    public int height() { return reader.getHeight(); }
    public synchronized boolean ready() { return !closed && latest != null && failure.isEmpty(); }
    public synchronized long sequence() { return sequence; }
    public synchronized String failure() { return failure; }

    private synchronized void receive(ImageReader source) {
        if (closed) return;
        try {
            Image next = source.acquireLatestImage();
            if (next == null) return;
            if (latest != null) latest.close();
            latest = next;
            sequence++;
            received = SystemClock.uptimeMillis();
            if (sequence == 1) readiness.run();
        } catch (RuntimeException error) {
            boolean first = failure.isEmpty();
            failure = error.toString();
            if (first) readiness.run();
        }
    }

    public synchronized Snapshot capture(int left, int top, int right, int bottom) throws IOException {
        if (!ready()) throw new IOException(failure.isEmpty() ? "Offscreen output has no available frame" : failure);
        if (left < 0 || top < 0 || right <= left || bottom <= top
                || right > latest.getWidth() || bottom > latest.getHeight())
            throw new IllegalArgumentException("Capture region is outside the offscreen frame");
        Image.Plane plane = latest.getPlanes()[0];
        int width = right - left, height = bottom - top;
        int[] colors = colors(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride(), left, top, width, height);
        return new Snapshot(Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888), sequence, received);
    }

    static int[] colors(java.nio.ByteBuffer pixels, int stride, int step, int left, int top, int width, int height) throws IOException {
        if (step < 4) throw new IOException("Unsupported offscreen pixel layout");
        int[] colors = new int[width * height];
        for (int y = 0, out = 0; y < height; y++) {
            int position = (y + top) * stride + left * step;
            for (int x = 0; x < width; x++, position += step) {
                int r = pixels.get(position) & 255, g = pixels.get(position + 1) & 255;
                int b = pixels.get(position + 2) & 255, a = pixels.get(position + 3) & 255;
                colors[out++] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return colors;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        reader.setOnImageAvailableListener(null, null);
        if (latest != null) latest.close();
        latest = null;
        reader.close();
    }
}
