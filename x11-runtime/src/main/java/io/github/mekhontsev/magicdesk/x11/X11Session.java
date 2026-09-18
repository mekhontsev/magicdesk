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
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;

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

    public enum WindowRole { APPLICATION, SPLASH, UNCLASSIFIED }
    public record Window(long id, String title, boolean mapped, Bitmap icon, WindowRole role, X11WindowManagement management,
            String instance, String className) {
        public boolean provisional() { return role != WindowRole.APPLICATION; }
        public boolean matchesClass(String expected) {
            return !expected.isEmpty() && (expected.equals(instance) || expected.equals(className));
        }
    }

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
    private int nextInspection;
    private final Map<Integer, Inspection> inspections = new LinkedHashMap<>();
    private record Inspection(long window, int limit, ArrayList<X11WindowInspection.Node> nodes,
            CompletableFuture<X11WindowInspection> result, Runnable timeout) { }

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
                cancelInspections();
                if (!nativeConnect(nativeHandle, descriptor.detachFd())) {
                    connected = false;
                    throw new IllegalStateException("Cannot connect X11 server");
                }
                connected = true;
                nativeData(nativeHandle, X11DataExchange.ENABLE, 0, 0, 0, 0, 0, 0, 0, "", -1);
                if (dpi != 0) nativeDpi(nativeHandle, dpi);
                windows.clear();
                nativeObserveWindows(nativeHandle);
                for (Output output : outputs.values()) {
                    output.frameAvailable = -1;
                    nativeBind(nativeHandle, output.id, output.window);
                    if (output.width > 0) nativeResize(nativeHandle, output.id, output.window, output.width, output.height);
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
            if (connected) nativeCloseWindow(nativeHandle, (int)windowId);
        });
    }

    /** Logical density of this X screen. Toolkits receive normal XSettings/RandR events. */
    public void setDpi(int value) {
        if (value < 24 || value > 1536) throw new IllegalArgumentException("Invalid X11 DPI");
        post(() -> {
            if (dpi == value) return;
            dpi = value;
            if (connected) nativeDpi(nativeHandle, dpi);
        });
    }

    /** Reports a completed host transition. The server ignores replies to superseded requests. */
    public void confirmWindowState(long windowId, X11WindowManagement.Request request, X11WindowManagement.State actual) {
        if (windowId <= 0 || windowId > 0xffffffffL) throw new IllegalArgumentException("Invalid X11 window ID");
        java.util.Objects.requireNonNull(request);
        java.util.Objects.requireNonNull(actual);
        post(() -> {
            if (connected) nativeConfirmWindowState(nativeHandle, (int)windowId, request.serial(), actual.fullscreen());
        });
    }

    private void onNativeWindow(int id, byte[] title, int[] pixels, boolean mapped, int role, X11WindowManagement management,
            byte[] instance, byte[] className) {
        Bitmap icon = pixels == null ? null : Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888);
        Window previous = windows.get(id);
        if (icon != null && previous != null && previous.icon() != null && icon.sameAs(previous.icon())) {
            icon.recycle();
            icon = previous.icon();
        }
        windows.put(id, new Window(Integer.toUnsignedLong(id),
                new String(title, java.nio.charset.StandardCharsets.UTF_8), mapped, icon,
                switch (role) { case 0 -> WindowRole.APPLICATION; case 1 -> WindowRole.SPLASH; default -> WindowRole.UNCLASSIFIED; }, management,
                new String(instance, java.nio.charset.StandardCharsets.ISO_8859_1),
                new String(className, java.nio.charset.StandardCharsets.ISO_8859_1)));
        windowsChanged = true;
    }

    private void onNativeWindowRemoved(int id) {
        windows.remove(id);
        windowsChanged = true;
    }

    private void onNativeWindowsCommitted() {
        if (!windowsChanged) return;
        windowsChanged = false;
        java.util.List<Window> snapshot = java.util.List.copyOf(windows.values());
        callbacks.execute(() -> { if (!closed) listener.onWindowsChanged(snapshot); });
    }

    public X11DataExchange dataExchange() { return dataExchange; }

    public CompletableFuture<X11WindowInspection> inspectWindow(long windowId, int limit) {
        if (windowId <= 0 || windowId > 0xffffffffL || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Invalid X11 inspection target or limit");
        return call(() -> {
            if (!connected) throw new IllegalStateException("X11 server is disconnected");
            if (inspections.size() >= 4) throw new IllegalStateException("Too many pending X11 inspections");
            if (nextInspection == Integer.MAX_VALUE) throw new IllegalStateException("Inspection identifiers exhausted");
            int serial = ++nextInspection;
            CompletableFuture<X11WindowInspection> result = new CompletableFuture<>();
            Runnable timeout = () -> {
                Inspection pending = inspections.remove(serial);
                if (pending != null) pending.result.completeExceptionally(
                        new IllegalStateException("X11 inspection response deadline expired"));
            };
            inspections.put(serial, new Inspection(windowId, limit, new ArrayList<>(), result, timeout));
            handler.postDelayed(timeout, 5000); // Bounds one protocol reply, not a polling interval.
            result.whenComplete((value, error) -> handler.post(() -> {
                Inspection pending = inspections.remove(serial);
                if (pending != null) handler.removeCallbacks(pending.timeout);
            }));
            nativeInspectWindow(nativeHandle, serial, (int)windowId, limit);
            return result;
        });
    }

    private void onNativeInspectionNode(int serial, X11WindowInspection.Node node) {
        Inspection pending = inspections.get(serial);
        if (pending == null) return;
        if (pending.nodes.size() >= pending.limit) {
            inspections.remove(serial);
            handler.removeCallbacks(pending.timeout);
            pending.result.completeExceptionally(new IllegalStateException("X11 inspection exceeds requested limit"));
        } else pending.nodes.add(node);
    }

    private void onNativeInspectionDone(int serial, int window, int focus, int focusKind,
            int width, int height, int count, boolean found, boolean truncated) {
        Inspection pending = inspections.remove(serial);
        if (pending == null) return;
        handler.removeCallbacks(pending.timeout);
        if (pending.window != Integer.toUnsignedLong(window) || count != pending.nodes.size()) {
            pending.result.completeExceptionally(new IllegalStateException("Invalid X11 inspection response"));
            return;
        }
        String kind = switch (focusKind) { case 0 -> "none"; case 1 -> "pointer_root"; case 2 -> "window"; default -> "unknown"; };
        pending.result.complete(new X11WindowInspection(pending.window, found, truncated,
                new X11WindowInspection.Focus(kind, Integer.toUnsignedLong(focus)),
                new X11WindowInspection.Bounds(0, 0, width, height), pending.nodes));
    }

    private void cancelInspections() {
        for (Inspection pending : inspections.values()) {
            handler.removeCallbacks(pending.timeout);
            pending.result.completeExceptionally(new IllegalStateException("X11 connection closed"));
        }
        inspections.clear();
    }

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
            if (connected) nativeBind(nativeHandle, output.id, output.window);
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
                    if (connected) nativeResize(nativeHandle, id, window, width, height);
                }
                nativeSurface(nativeHandle, id, surface, false);
                return null;
            }, true);
        }

        /** Coordinates are normalized to the displayed X11 content, excluding letterboxing. */
        public void pointer(float x, float y, int button, boolean down) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || button < 0 || button > 7)
                throw new IllegalArgumentException("Invalid pointer event");
            if (released || closed) return;
            handler.post(() -> { if (acceptsInput()) nativePointer(nativeHandle, id, window, x, y, button, down); });
        }

        public void key(int androidKeyCode, int scanCode, boolean down) {
            if (androidKeyCode < 0 || scanCode < 0 || scanCode > 247)
                throw new IllegalArgumentException("Invalid keyboard event");
            if (released || closed) return;
            handler.post(() -> { if (acceptsInput()) nativeKey(nativeHandle, id, window, androidKeyCode, scanCode, down); });
        }

        public void focus() {
            if (released || closed) return;
            handler.post(() -> { if (acceptsInput()) nativeFocus(nativeHandle, id, window); });
        }

        public void text(String text) {
            java.util.Objects.requireNonNull(text);
            if (released || closed) return;
            handler.post(() -> { if (acceptsInput()) nativeText(nativeHandle, id, window, text); });
        }

        private boolean acceptsInput() { return !released && !closed && connected; }

        /** Releases only this presentation, never the X client or server process. */
        @Override public void close() {
            if (released || closed) return;
            dispatch(() -> {
                if (!released) {
                    released = true;
                    if (connected) nativeRelease(nativeHandle, id, window);
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
        cancelInspections();
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
                    cancelInspections();
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
    private static native void nativeBind(long handle, int output, int window);
    private static native void nativeResize(long handle, int output, int window, int width, int height);
    private static native void nativePointer(long handle, int output, int window, float x, float y, int button, boolean down);
    private static native void nativeKey(long handle, int output, int window, int androidKeyCode, int scanCode, boolean down);
    private static native void nativeFocus(long handle, int output, int window);
    private static native void nativeRelease(long handle, int output, int window);
    private static native void nativeObserveWindows(long handle);
    private static native void nativeInspectWindow(long handle, int serial, int window, int limit);
    private static native void nativeCloseWindow(long handle, int window);
    private static native void nativeDpi(long handle, int dpi);
    private static native void nativeConfirmWindowState(long handle, int window, int requestSerial, boolean fullscreen);
    private static native void nativeText(long handle, int output, int window, String text);
    private static native void nativeData(long handle, int operation, int channel, int serial, int offer,
            int output, int window, int x, int y, String type, int descriptor);
    private static native void nativeDestroy(long handle);
}
