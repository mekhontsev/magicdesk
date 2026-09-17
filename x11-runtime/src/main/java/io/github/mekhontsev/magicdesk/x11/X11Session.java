package io.github.mekhontsev.magicdesk.x11;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/** One server connection and Present queue, independently retained from its output windows. */
public final class X11Session implements AutoCloseable {
    static { System.loadLibrary("Xlorie"); }

    public interface Listener {
        void onFrame(Output output, int width, int height, boolean available);
        void onDisconnected();
        default void onWindowsChanged(java.util.List<Window> windows) { }
        default void onDataOffer(X11DataExchange.Offer offer) { }
        default void onDragEvent(int operation, int output, boolean accepted) { }
    }

    public record Window(long id, String title, boolean mapped, Bitmap icon) { }

    private static final int BIND = 0, RESIZE = 1, POINTER = 2, KEY = 3, RELEASE = 4, FOCUS = 5;
    private final HandlerThread thread = new HandlerThread("X11Session");
    private final Handler handler;
    private final Executor callbacks;
    private final Listener listener;
    private final Map<Integer, Output> outputs = new LinkedHashMap<>();
    private final Map<Integer, Window> windows = new LinkedHashMap<>();
    private final Object submissions = new Object();
    private FutureTask<Void> shutdown;
    private volatile boolean closed;
    private boolean connected;
    private int nextOutputId;
    private long nativeHandle;
    private final X11DataExchange dataExchange;
    private boolean windowsChanged;
    private int dpi;

    public X11Session(Executor callbacks, Listener listener) {
        this.callbacks = java.util.Objects.requireNonNull(callbacks);
        this.listener = java.util.Objects.requireNonNull(listener);
        thread.start();
        handler = new Handler(thread.getLooper());
        dataExchange = new X11DataExchange(handler, callbacks, new X11DataExchange.Listener() {
            @Override public void onOffer(X11DataExchange.Offer offer) { listener.onDataOffer(offer); }
            @Override public void onDragEvent(int operation, int output, boolean accepted) {
                listener.onDragEvent(operation, output, accepted);
            }
        }, (operation, channel, serial, offer, output, window, x, y, type, descriptor) -> {
            final ParcelFileDescriptor copy;
            try { copy = descriptor == null ? null : ParcelFileDescriptor.dup(descriptor.getFileDescriptor()); }
            catch (java.io.IOException error) { throw new IllegalStateException("Cannot retain content descriptor", error); }
            Runnable send = () -> {
                try (copy) {
                    if (!closed && connected) nativeData(nativeHandle, operation, channel, serial, offer, output, window, x, y,
                            type, copy == null ? -1 : copy.getFd());
                } catch (java.io.IOException error) { android.util.Log.w("X11Content", "Descriptor close failed", error); }
            };
            if (!handler.post(send) && copy != null) try { copy.close(); } catch (java.io.IOException ignored) { }
        });
        try {
            call(() -> {
                nativeHandle = nativeCreate();
                if (nativeHandle == 0) throw new IllegalStateException("Cannot initialize X11 renderer");
                return null;
            });
        } catch (RuntimeException e) {
            thread.quitSafely();
            throw e;
        }
    }

    /** Takes ownership of the descriptor supplied by this session's server. */
    public void connect(ParcelFileDescriptor descriptor) {
        java.util.Objects.requireNonNull(descriptor);
        try (descriptor) {
            call(() -> {
                if (!nativeConnect(nativeHandle, descriptor.detachFd())) {
                    connected = false;
                    throw new IllegalStateException("Cannot connect X11 server");
                }
                connected = true;
                nativeData(nativeHandle, X11DataExchange.ENABLE, 0, 0, 0, 0, 0, 0, 0, "", -1);
                if (dpi != 0) nativeCommand(nativeHandle, 0, 0, 9, dpi, 0, 0, false);
                windows.clear();
                nativeCommand(nativeHandle, 0, 0, 7, 0, 0, 0, false);
                for (Output output : outputs.values()) {
                    output.frameAvailable = -1;
                    output.send(BIND, 0, 0, 0, false);
                    if (output.width > 0) output.send(RESIZE, output.width, output.height, 0, false);
                }
                return null;
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot close X11 connection descriptor", e);
        }
    }

    /** ICCCM WM_DELETE_WINDOW, or client termination when that protocol is unsupported. */
    public void closeWindow(long windowId) {
        if (windowId <= 0 || windowId > 0xffffffffL) throw new IllegalArgumentException("Invalid X11 window ID");
        post(() -> {
            if (connected) nativeCommand(nativeHandle, 0, (int)windowId, 8, 0, 0, 0, false);
        });
    }

    /** Logical density of this X screen. Toolkits receive normal XSettings/RandR events. */
    public void setDpi(int value) {
        if (value < 24 || value > 1536) throw new IllegalArgumentException("Invalid X11 DPI");
        post(() -> {
            if (dpi == value) return;
            dpi = value;
            if (connected) nativeCommand(nativeHandle, 0, 0, 9, dpi, 0, 0, false);
        });
    }

    private void onNativeWindow(int id, byte[] title, int[] pixels, boolean removed, boolean mapped) {
        if (removed) windows.remove(id);
        else {
            Bitmap icon = pixels == null ? null : Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888);
            Window previous = windows.get(id);
            if (icon != null && previous != null && previous.icon() != null && icon.sameAs(previous.icon())) {
                icon.recycle();
                icon = previous.icon();
            }
            windows.put(id, new Window(Integer.toUnsignedLong(id),
                    new String(title, java.nio.charset.StandardCharsets.UTF_8), mapped, icon));
        }
        windowsChanged = true;
    }

    private void onNativeWindowsCommitted() {
        if (!windowsChanged) return;
        windowsChanged = false;
        java.util.List<Window> snapshot = java.util.List.copyOf(windows.values());
        callbacks.execute(() -> { if (!closed) listener.onWindowsChanged(snapshot); });
    }

    public X11DataExchange dataExchange() { return dataExchange; }

    private void onNativeData(int operation, int channel, int serial, int offer, int output, int window,
            int x, int y, String type, int descriptor) {
        dataExchange.receive(operation, channel, serial, offer, output, window, x, y, type,
                descriptor < 0 ? null : ParcelFileDescriptor.adoptFd(descriptor));
    }

    /** XID zero selects the whole X screen, not an Android display ID. */
    public Output openOutput(long windowId) {
        if (windowId < 0 || windowId > 0xffffffffL) throw new IllegalArgumentException("Invalid X11 window ID");
        return call(() -> {
            if (nextOutputId == Integer.MAX_VALUE) throw new IllegalStateException("Output identifiers exhausted");
            Output output = new Output(++nextOutputId, (int)windowId);
            nativeSurface(nativeHandle, output.id, null, false);
            outputs.put(output.id, output);
            output.send(BIND, 0, 0, 0, false);
            return output;
        });
    }

    public final class Output implements AutoCloseable {
        private final int id, window;
        private volatile boolean released;
        private int width, height;
        private int frameWidth = -1, frameHeight = -1, frameAvailable = -1;

        private Output(int id, int window) { this.id = id; this.window = window; }
        public int id() { return id; }
        public long windowId() { return Integer.toUnsignedLong(window); }

        public void setSurface(Surface surface, int width, int height) {
            if (surface != null && (width < 1 || height < 1 || width > 16384 || height > 16384))
                throw new IllegalArgumentException("Invalid X11 output size");
            if (released || closed) return;
            dispatch(() -> {
                if (released) return null;
                if (surface != null) {
                    this.width = width;
                    this.height = height;
                    send(RESIZE, width, height, 0, false);
                }
                nativeSurface(nativeHandle, id, surface, false);
                return null;
            }, true);
        }

        /** Coordinates are normalized to the displayed X11 content, excluding letterboxing. */
        public void pointer(float x, float y, int button, boolean down) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || button < 0 || button > 7)
                throw new IllegalArgumentException("Invalid pointer event");
            input(POINTER, Math.round(Math.max(0, Math.min(1, x)) * 10000),
                    Math.round(Math.max(0, Math.min(1, y)) * 10000), button, down);
        }

        public void key(int androidKeyCode, int scanCode, boolean down) {
            if (androidKeyCode < 0 || scanCode < 0 || scanCode > 247)
                throw new IllegalArgumentException("Invalid keyboard event");
            input(KEY, androidKeyCode, 0, scanCode > 0 ? scanCode + 8 : 0, down);
        }

        public void focus() { input(FOCUS, 0, 0, 0, false); }

        public void text(String text) {
            java.util.Objects.requireNonNull(text);
            if (released || closed) return;
            handler.post(() -> {
                if (!released && !closed && connected) {
                    nativeText(nativeHandle, id, window, text);
                }
            });
        }

        private void input(int operation, int x, int y, int detail, boolean down) {
            if (released || closed) return;
            handler.post(() -> { if (!released && !closed) send(operation, x, y, detail, down); });
        }

        private void send(int operation, int x, int y, int detail, boolean down) {
            if (connected) nativeCommand(nativeHandle, id, window, operation, x, y, detail, down);
        }

        /** Releases only this presentation, never the X client or server process. */
        @Override public void close() {
            if (released || closed) return;
            dispatch(() -> {
                if (!released) {
                    released = true;
                    send(RELEASE, 0, 0, 0, false);
                    nativeSurface(nativeHandle, id, null, true);
                    outputs.remove(id);
                }
                return null;
            }, true);
        }
    }

    private void onNativeFrame(int id, int window, int width, int height, int available) {
        Output output = outputs.get(id);
        if (output == null || output.window != window) return;
        if (output.frameWidth == width && output.frameHeight == height && output.frameAvailable == available) return;
        output.frameWidth = width;
        output.frameHeight = height;
        output.frameAvailable = available;
        callbacks.execute(() -> {
            if (!closed && !output.released) listener.onFrame(output, width, height, available != 0);
        });
    }

    private void onNativeDisconnected() {
        connected = false;
        dataExchange.disconnected();
        callbacks.execute(() -> { if (!closed) listener.onDisconnected(); });
    }

    @Override public void close() {
        dataExchange.close();
        FutureTask<Void> task;
        synchronized (submissions) {
            if (closed) return;
            if (shutdown == null) {
                shutdown = new FutureTask<>(() -> {
                    for (Output output : outputs.values()) output.released = true;
                    outputs.clear();
                    nativeDestroy(nativeHandle);
                    nativeHandle = 0;
                    closed = true;
                    thread.quitSafely();
                    return null;
                });
                submit(shutdown);
            }
            task = shutdown;
        }
        await(task);
    }

    private <T> T call(Callable<T> action) {
        return dispatch(action, false);
    }

    // Commands have no resource-release acknowledgement. Keep UI/focus callbacks
    // independent of server progress; Surface changes still use dispatch below.
    private void post(Runnable action) {
        synchronized (submissions) {
            if (shutdown == null && !closed) handler.post(action);
        }
    }

    private <T> T dispatch(Callable<T> action, boolean optional) {
        FutureTask<T> task;
        synchronized (submissions) {
            if (shutdown != null || closed) {
                if (!optional) throw new IllegalStateException("X11 session is closed");
                task = null;
            } else {
                task = new FutureTask<>(action);
                submit(task);
            }
        }
        if (task != null) return await(task);
        // Surface teardown must acknowledge any in-flight renderer shutdown too.
        close();
        return null;
    }

    private void submit(FutureTask<?> task) {
        if (Looper.myLooper() == handler.getLooper()) task.run();
        else if (!handler.post(task)) throw new IllegalStateException("X11 session stopped");
    }

    private static <T> T await(FutureTask<T> task) {
        boolean interrupted = false;
        try {
            // A submitted native operation owns resources until its acknowledgement, even if the caller is interrupted.
            for (;;) {
                try { return task.get(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } catch (ExecutionException e) {
            throw new IllegalStateException("X11 operation failed", e.getCause());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private native long nativeCreate();
    private static native boolean nativeConnect(long handle, int fd);
    private static native void nativeSurface(long handle, int output, Surface surface, boolean release);
    private static native void nativeCommand(long handle, int output, int window, int operation, int x, int y, int detail, boolean down);
    private static native void nativeText(long handle, int output, int window, String text);
    private static native void nativeData(long handle, int operation, int channel, int serial, int offer,
            int output, int window, int x, int y, String type, int descriptor);
    private static native void nativeDestroy(long handle);
}
