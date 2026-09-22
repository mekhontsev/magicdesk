package io.github.mekhontsev.magicdesk.wayland;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns one Android buffer queue; its blocking writes never run on session control. */
final class WaylandFramePresenter implements AutoCloseable {
    static { System.loadLibrary("magicdesk_wayland_host"); }
    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<String> failure;
    private long surface;
    private ParcelFileDescriptor frame;
    private int frameWidth, frameHeight;

    WaylandFramePresenter(long id, Consumer<String> failure) {
        this.failure = failure;
        thread = new HandlerThread("WaylandOutput-" + id);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    boolean setSurface(Surface nextSurface) {
        if (closed.get()) return false;
        long next = nextSurface == null ? 0 : nativeAcquire(nextSurface);
        if (nextSurface != null && next == 0) {
            failure.accept("Cannot retain Android Surface");
            return false;
        }
        if (handler.post(() -> {
            if (closed.get()) { nativeRelease(next); return; }
            nativeRelease(surface);
            surface = next;
            if (surface == 0) clearFrame();
            else if (frame != null && !nativePresent(surface, frame.getFd(), frameWidth, frameHeight))
                failure.accept("Cannot present retained Wayland frame");
        })) return true;
        nativeRelease(next);
        return false;
    }

    void present(ParcelFileDescriptor pixels, int width, int height, Runnable consumed) {
        if (!handler.post(() -> {
            try {
                if (closed.get() || surface == 0) { closeDescriptor(pixels); return; }
                clearFrame();
                if (pixels == null) { nativeClear(surface); return; }
                if (!nativePresent(surface, pixels.getFd(), width, height)) {
                    closeDescriptor(pixels);
                    failure.accept("Invalid or unavailable Wayland frame");
                    return;
                }
                frame = pixels;
                frameWidth = width;
                frameHeight = height;
            } finally { consumed.run(); }
        })) {
            closeDescriptor(pixels);
            consumed.run();
        }
    }

    private void clearFrame() {
        closeDescriptor(frame);
        frame = null;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        handler.post(() -> {
            clearFrame();
            nativeRelease(surface);
            surface = 0;
            thread.quitSafely();
        });
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
    }

    private static native long nativeAcquire(Surface surface);
    private static native void nativeRelease(long surface);
    private static native boolean nativePresent(long surface, int descriptor, int width, int height);
    private static native void nativeClear(long surface);
}
