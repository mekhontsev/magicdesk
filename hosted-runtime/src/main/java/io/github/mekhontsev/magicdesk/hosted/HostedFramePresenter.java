package io.github.mekhontsev.magicdesk.hosted;

import android.os.Handler;
import android.os.HandlerThread;
import android.hardware.HardwareBuffer;
import android.view.Surface;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns one Android buffer queue; its blocking writes never run on session control. */
public final class HostedFramePresenter implements AutoCloseable {
    static { System.loadLibrary("magicdesk_graphics_host"); }
    private final HandlerThread thread;
    private final Handler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private long surface;
    private volatile long requestedGeneration;
    private long surfaceGeneration;

    public HostedFramePresenter(long id) {
        thread = new HandlerThread("HostedOutput-" + id);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Retained before the caller can release its Java Surface; transferred once to the worker. */
    public static final class SurfaceLease implements AutoCloseable {
        private long handle;

        public SurfaceLease(Surface surface) throws IOException {
            handle = surface == null ? 0 : nativeAcquire(surface);
            if (surface != null && handle == 0) throw new IOException("Cannot retain Android Surface");
        }

        long take() { long result = handle; handle = 0; return result; }
        @Override public void close() { nativeRelease(take()); }
    }

    public boolean setSurface(SurfaceLease lease, long generation) {
        long next = lease.take();
        if (closed.get()) { nativeRelease(next); return false; }
        requestedGeneration = generation;
        if (handler.post(() -> {
            if (closed.get()) { nativeRelease(next); return; }
            nativeRelease(surface);
            surface = next;
            surfaceGeneration = generation;
        })) return true;
        nativeRelease(next);
        return false;
    }

    public void present(HostedFrame frame, long generation, Consumer<Boolean> consumed) {
        if (!handler.post(() -> {
            boolean submitted = false;
            try {
                if (closed.get() || surface == 0 || generation != requestedGeneration || generation != surfaceGeneration) return;
                if (frame == null) { nativeClear(surface); return; }
                submitted = nativePresent(surface, frame.buffer, frame.pixels == null ? -1 : frame.pixels.getFd(),
                        frame.fence == null ? -1 : frame.fence.getFd(), frame.width, frame.height);
            } finally {
                HostedFrame.close(frame);
                consumed.accept(submitted);
            }
        })) {
            HostedFrame.close(frame);
            consumed.accept(false);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        handler.post(() -> {
            nativeRelease(surface);
            surface = 0;
            thread.quitSafely();
        });
    }

    private static native long nativeAcquire(Surface surface);
    private static native void nativeRelease(long surface);
    private static native boolean nativePresent(long surface, HardwareBuffer buffer, int descriptor, int fence, int width, int height);
    private static native void nativeClear(long surface);
}
