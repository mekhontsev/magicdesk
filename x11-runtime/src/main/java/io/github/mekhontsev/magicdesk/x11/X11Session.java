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
import java.util.List;
import io.github.mekhontsev.magicdesk.hosted.FramePresentation;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints;

/** One server connection and Present queue, independently retained from its output windows. */
public final class X11Session implements AutoCloseable {
    static { System.loadLibrary("Xlorie"); }

    public interface Listener {
        void onFrame(Output output, int width, int height, boolean available);
        void onDisconnected();
        default void onWindowsChanged(java.util.List<Window> windows) { }
        default void onDataOffer(X11DataExchange.Offer offer) { }
        default void onDragEvent(int operation, int output, boolean accepted) { }
        default void onCursor(Output output, Cursor cursor) { }
        default void onWindowGesture(long window, io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture gesture) { }
    }

    /** Immutable shape in X content pixels. A null image is either hidden or the host default. */
    public record Cursor(Bitmap image, int hotspotX, int hotspotY, boolean hidden) {
        public static final Cursor DEFAULT = new Cursor(null, 0, 0, false);
        public static final Cursor HIDDEN = new Cursor(null, 0, 0, true);
    }

    public enum WindowRole { APPLICATION, SPLASH, UNCLASSIFIED, DIALOG }
    public record Window(long id, String title, boolean mapped, Bitmap icon, WindowRole role, X11WindowManagement management,
            String instance, String className, HostedWindowLayout layout) {
        public boolean provisional() { return role == WindowRole.SPLASH || role == WindowRole.UNCLASSIFIED; }
        public boolean applicationWindow() { return role == WindowRole.APPLICATION && layout.parent() == 0; }
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
    private int nextShell;
    private ShellBinding shell;
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
                if (shell != null) shell.end("X11 server connection replaced");
                cancelInspections();
                for (Output output : outputs.values()) output.cursor(Cursor.DEFAULT);
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
                    if (output.parent != null) nativeBindDependents(nativeHandle, output.id, output.window, output.parent.id);
                    else nativeBind(nativeHandle, output.id, output.window);
                    if (!output.borrowed() && output.width > 0) nativeResize(nativeHandle, output.id, output.window, output.width, output.height);
                }
                return null;
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot close X11 connection descriptor", e);
        }
    }

    /** WM_DELETE_WINDOW; force (or an unsupported protocol) disconnects the owning X client. */
    public void closeWindow(long windowId, boolean force) {
        if (windowId <= 0 || windowId > 0xffffffffL) throw new IllegalArgumentException("Invalid X11 window ID");
        post(() -> {
            if (connected) nativeCloseWindow(nativeHandle, (int)windowId, force);
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
            byte[] instance, byte[] className, int parent, int width, int height,
            int minWidth, int minHeight, int maxWidth, int maxHeight) {
        Bitmap icon = pixels == null ? null : Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888);
        Window previous = windows.get(id);
        if (icon != null && previous != null && previous.icon() != null && icon.sameAs(previous.icon())) {
            icon.recycle();
            icon = previous.icon();
        }
        windows.put(id, new Window(Integer.toUnsignedLong(id),
                new String(title, java.nio.charset.StandardCharsets.UTF_8), mapped, icon,
                switch (role) { case 0 -> WindowRole.APPLICATION; case 1 -> WindowRole.SPLASH;
                    case 3 -> WindowRole.DIALOG; default -> WindowRole.UNCLASSIFIED; }, management,
                new String(instance, java.nio.charset.StandardCharsets.ISO_8859_1),
                new String(className, java.nio.charset.StandardCharsets.ISO_8859_1),
                new HostedWindowLayout(Integer.toUnsignedLong(parent), width, height,
                        new HostedWindowConstraints(minWidth, minHeight, maxWidth, maxHeight))));
        windowsChanged = true;
    }

    public void confirmMaximized(long windowId, long serial, io.github.mekhontsev.magicdesk.hosted.HostedMaximization actual) {
        if (windowId <= 0 || windowId > 0xffffffffL) throw new IllegalArgumentException("Invalid X11 window ID");
        java.util.Objects.requireNonNull(actual);
        int axes = (actual.horizontal ? 1 : 0) | (actual.vertical ? 2 : 0);
        post(() -> { if (connected) nativeConfirmMaximized(nativeHandle, (int)windowId, (int)serial, axes); });
    }

    private void onNativeWindowGesture(int id, int direction) {
        var gesture = X11WindowGestures.decode(direction);
        if (gesture != null) callbacks.execute(() -> { if (!closed) listener.onWindowGesture(Integer.toUnsignedLong(id), gesture); });
    }

    private void onNativeWindowRemoved(int id) {
        windows.remove(id);
        windowsChanged = true;
    }

    private void onNativeWindowsCommitted() {
        if (shell != null) shell.publish();
        if (!windowsChanged) return;
        windowsChanged = false;
        java.util.List<Window> snapshot = java.util.List.copyOf(windows.values());
        callbacks.execute(() -> { if (!closed) listener.onWindowsChanged(snapshot); });
    }

    public X11DataExchange dataExchange() { return dataExchange; }

    public interface ShellListener {
        void changed();
        void closed(String reason);
    }

    /** An explicit, revocable shell catalog lease. It does not acquire the guest WM selection. */
    public ShellBinding bindShell(int width, int height, ShellListener listener) {
        shellSize(width, height);
        java.util.Objects.requireNonNull(listener);
        return call(() -> {
            if (!connected || shell != null) throw new IllegalStateException("X11 shell already bound or unavailable");
            for (var output : outputs.values()) if (output.window == 0 && !output.released)
                throw new IllegalStateException("A whole-desktop viewer owns this X screen");
            if (nextShell == Integer.MAX_VALUE) throw new IllegalStateException("Shell identifiers exhausted");
            var binding = new ShellBinding(++nextShell, listener);
            shell = binding;
            nativeShell(nativeHandle, binding.id, width, height);
            // EVENT_WAIT: server shell admission; expiry releases the lease and fails binding.
            handler.postDelayed(binding.timeout, 5000);
            return binding;
        });
    }

    private static void shellSize(int width, int height) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384)
            throw new IllegalArgumentException("Invalid X11 shell output size");
    }

    public final class ShellBinding implements AutoCloseable {
        private final int id;
        private final ShellListener listener;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final Map<Integer, X11ShellSurface> catalog = new LinkedHashMap<>();
        private volatile List<X11ShellSurface> snapshot = List.of();
        private volatile boolean ended;
        private boolean changed;
        private final Runnable timeout = () -> end("X11 shell admission deadline expired");

        private ShellBinding(int id, ShellListener listener) { this.id = id; this.listener = listener; }
        public CompletableFuture<Void> ready() { return ready; }
        public List<X11ShellSurface> surfaces() { return snapshot; }
        public void resize(int width, int height) {
            shellSize(width, height);
            post(() -> { if (!ended) nativeShell(nativeHandle, id, width, height); });
        }
        public Output openOutput(long windowId) {
            return call(() -> {
                if (ended || shell != this || !ready.isDone() || ready.isCompletedExceptionally() ||
                        windowId <= 0 || windowId > 0xffffffffL || !catalog.containsKey((int)windowId))
                    throw new IllegalStateException("X11 shell surface is unavailable");
                if (nextOutputId == Integer.MAX_VALUE) throw new IllegalStateException("Output identifiers exhausted");
                var output = new Output(++nextOutputId, (int)windowId, true);
                nativeSurface(nativeHandle, output.id, null, false);
                outputs.put(output.id, output);
                nativeBindShell(nativeHandle, output.id, output.window);
                return output;
            });
        }
        private void publish() {
            if (ended || !changed) return;
            changed = false;
            snapshot = List.copyOf(catalog.values());
            callbacks.execute(() -> { if (!closed && !ended) listener.changed(); });
        }
        private void end(String reason) {
            if (ended) return;
            ended = true;
            handler.removeCallbacks(timeout);
            if (shell == this) shell = null;
            if (connected) nativeShell(nativeHandle, id, 0, 0);
            snapshot = List.of();
            catalog.clear();
            for (var output : List.copyOf(outputs.values())) if (output.shellOutput) output.release();
            ready.completeExceptionally(new IllegalStateException(reason.isEmpty() ? "X11 shell released" : reason));
            callbacks.execute(() -> listener.closed(reason));
        }
        @Override public void close() { dispatch(() -> { end(""); return null; }, true); }
    }

    private void onNativeShell(int owner, int window, int[] fields) {
        var binding = shell;
        if (binding == null || binding.id != owner || binding.ended) return;
        try {
            if (fields == null) binding.catalog.remove(window);
            else binding.catalog.put(window, X11ShellSurface.decode(window, fields));
            binding.changed = true;
        } catch (RuntimeException error) { binding.end("Invalid X11 shell metadata: " + error.getMessage()); }
    }

    private void onNativeShellState(int owner, boolean available) {
        var binding = shell;
        if (binding == null || binding.id != owner) return;
        handler.removeCallbacks(binding.timeout);
        if (available) binding.ready.complete(null);
        else binding.end("X11 shell ownership unavailable (guest window manager or invalid output)");
    }

    // Renderer callbacks do not touch the connection's control-thread state.
    private void onNativePresented(int id, int serial, boolean success) {
        handler.post(() -> {
            var output = outputs.get(id);
            if (output == null || output.released || !output.presentation.accepts(serial)) return;
            if (success) output.presentation.submitted(serial);
            else output.presentation.fail(new IllegalStateException("X11 frame submission failed"));
        });
    }

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
            // EVENT_WAIT: inspection reply; expiry fails the request, never supplies a partial snapshot.
            handler.postDelayed(timeout, 5000);
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
            if (windowId == 0 && shell != null) throw new IllegalStateException("Release shell integration before viewing the whole X screen");
            if (nextOutputId == Integer.MAX_VALUE) throw new IllegalStateException("Output identifiers exhausted");
            Output output = new Output(++nextOutputId, (int)windowId, false);
            nativeSurface(nativeHandle, output.id, null, false);
            outputs.put(output.id, output);
            if (connected) nativeBind(nativeHandle, output.id, output.window);
            return output;
        });
    }

    public final class Output implements AutoCloseable {
        private final int id, window;
        private final boolean shellOutput;
        private final FramePresentation presentation = new FramePresentation();
        private volatile boolean released;
        private int width, height;
        private int frameWidth = -1, frameHeight = -1, frameAvailable = -1;
        private Cursor cursor = Cursor.DEFAULT;
        private boolean cursorPending;
        private Output parent, dependents;
        private java.util.function.Consumer<X11FamilyGeometry> geometryListener;

        private Output(int id, int window, boolean shellOutput) { this.id = id; this.window = window; this.shellOutput = shellOutput; }
        public int id() { return id; }
        public long windowId() { return Integer.toUnsignedLong(window); }

        private boolean borrowed() { return shellOutput || parent != null; }

        /** A single borrowed dependent presentation. Closing it restores the parent's complete family. */
        public Output borrowDependents(java.util.function.Consumer<X11FamilyGeometry> listener) {
            java.util.Objects.requireNonNull(listener);
            return call(() -> {
                if (released || !connected || window == 0 || borrowed() || dependents != null)
                    throw new IllegalStateException("X11 family cannot be borrowed");
                if (nextOutputId == Integer.MAX_VALUE) throw new IllegalStateException("Output identifiers exhausted");
                Output child = new Output(++nextOutputId, window, false);
                child.parent = this;
                child.geometryListener = listener;
                nativeSurface(nativeHandle, child.id, null, false);
                outputs.put(child.id, child);
                dependents = child;
                nativeBindDependents(nativeHandle, child.id, window, id);
                return child;
            });
        }

        public void setSurface(Surface surface, int width, int height) {
            if (surface != null && (width < 1 || height < 1 || width > 16384 || height > 16384))
                throw new IllegalArgumentException("Invalid X11 output size");
            if (released || closed) return;
            dispatch(() -> {
                if (released) return null;
                if (surface != null) {
                    this.width = width;
                    this.height = height;
                    if (connected && !borrowed()) nativeResize(nativeHandle, id, window, width, height);
                }
                presentation.invalidate("X11 Surface replaced");
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

        public void blur() {
            if (!shellOutput || released || closed) return;
            blurFamily();
        }

        /** The Android owner left a split family; switching between its roots must not call this. */
        public void blurFamily() {
            if (released || closed) return;
            handler.post(() -> { if (acceptsInput()) nativeBlur(nativeHandle, id, window); });
        }

        /** Shell viewports never resize or move the X client's window family. */
        public CompletableFuture<Void> present(Surface surface, X11ShellSurface.Rect viewport) {
            java.util.Objects.requireNonNull(surface);
            java.util.Objects.requireNonNull(viewport);
            if (!borrowed() || viewport.left() < -16384 || viewport.top() < -16384 ||
                    viewport.right() > 16384 || viewport.bottom() > 16384 ||
                    viewport.right() <= viewport.left() || viewport.bottom() <= viewport.top())
                throw new IllegalArgumentException("Invalid X11 shell viewport");
            return call(() -> {
                if (released || !connected) throw new IllegalStateException("X11 output unavailable");
                var completion = new CompletableFuture<Void>();
                long generation = presentation.begin(completion);
                if (generation > Integer.MAX_VALUE) throw new IllegalStateException("Presentation identifiers exhausted");
                try {
                    nativeSurface(nativeHandle, id, surface, false);
                    nativePresent(nativeHandle, id, window, (int)generation, viewport.left(), viewport.top(), viewport.right(), viewport.bottom());
                } catch (RuntimeException error) { presentation.fail(error); }
                Runnable timeout = () -> {
                    if (presentation.accepts(generation)) presentation.fail(new IllegalStateException("X11 frame presentation deadline expired"));
                };
                // EVENT_WAIT: matching renderer swap; expiry revokes shell input admission through the failed receipt.
                handler.postDelayed(timeout, 5000);
                completion.whenComplete((ignored, error) -> handler.removeCallbacks(timeout));
                return completion;
            });
        }

        public void text(String text) {
            java.util.Objects.requireNonNull(text);
            if (released || closed) return;
            handler.post(() -> { if (acceptsInput()) nativeText(nativeHandle, id, window, text); });
        }

        private boolean acceptsInput() { return !released && !closed && connected; }

        private void cursor(Cursor next) {
            synchronized (this) {
                cursor = next;
                if (cursorPending) return;
                cursorPending = true;
            }
            callbacks.execute(() -> {
                Cursor current;
                synchronized (this) { current = cursor; cursorPending = false; }
                if (!closed && !released) listener.onCursor(this, current);
            });
        }

        /** Releases only this presentation, never the X client or server process. */
        @Override public void close() {
            if (released || closed) return;
            dispatch(() -> {
                release();
                return null;
            }, true);
        }

        private void release() {
            if (released) return;
            released = true;
            if (dependents != null) dependents.release();
            if (parent != null && parent.dependents == this) parent.dependents = null;
            presentation.invalidate("X11 output released");
            if (connected) nativeRelease(nativeHandle, id, window);
            nativeSurface(nativeHandle, id, null, true);
            outputs.remove(id);
        }
    }

    private void onNativeFamily(int id, int[] fields) {
        Output output = outputs.get(id);
        if (output == null || output.released || output.geometryListener == null) return;
        var geometry = X11FamilyGeometry.decode(fields);
        callbacks.execute(() -> {
            if (!closed && !output.released) output.geometryListener.accept(geometry);
        });
    }

    private void onNativeFrame(int id, int window, int width, int height, int available) {
        Output output = outputs.get(id);
        if (output == null || output.window != window) return;
        if (output.frameWidth == width && output.frameHeight == height && output.frameAvailable == available) return;
        output.frameWidth = width;
        output.frameHeight = height;
        output.frameAvailable = available;
        if (available == 0) output.cursor(Cursor.DEFAULT);
        callbacks.execute(() -> {
            if (!closed && !output.released) listener.onFrame(output, width, height, available != 0);
        });
    }

    private void onNativeDisconnected() {
        connected = false;
        if (shell != null) shell.end("X11 server disconnected");
        for (Output output : outputs.values()) output.presentation.invalidate("X11 server disconnected");
        for (Output output : outputs.values()) output.cursor(Cursor.DEFAULT);
        cancelInspections();
        dataExchange.disconnected();
        callbacks.execute(() -> { if (!closed) listener.onDisconnected(); });
    }

    private void onNativeCursor(int id, int window, int kind, int width, int height,
            int hotspotX, int hotspotY, int[] pixels) {
        Output output = outputs.get(id);
        if (output == null || output.released || output.window != window || !connected) return;
        Cursor cursor = kind == 1 ? Cursor.HIDDEN : Cursor.DEFAULT;
        if (kind == 2) cursor = new Cursor(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888),
                hotspotX, hotspotY, false);
        output.cursor(cursor);
    }

    @Override public void close() {
        dataExchange.close();
        FutureTask<Void> task;
        synchronized (submissions) {
            if (closed) return;
            if (shutdown == null) {
                shutdown = new FutureTask<>(() -> {
                    if (shell != null) shell.end("X11 session closed");
                    cancelInspections();
                    for (Output output : outputs.values()) {
                        output.released = true;
                        output.presentation.invalidate("X11 session closed");
                    }
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
    private static native void nativeBindShell(long handle, int output, int window);
    private static native void nativeBindDependents(long handle, int output, int window, int parent);
    private static native void nativeShell(long handle, int owner, int width, int height);
    private static native void nativePresent(long handle, int output, int window, int serial, int left, int top, int right, int bottom);
    private static native void nativeResize(long handle, int output, int window, int width, int height);
    private static native void nativePointer(long handle, int output, int window, float x, float y, int button, boolean down);
    private static native void nativeKey(long handle, int output, int window, int androidKeyCode, int scanCode, boolean down);
    private static native void nativeFocus(long handle, int output, int window);
    private static native void nativeBlur(long handle, int output, int window);
    private static native void nativeRelease(long handle, int output, int window);
    private static native void nativeObserveWindows(long handle);
    private static native void nativeInspectWindow(long handle, int serial, int window, int limit);
    private static native void nativeCloseWindow(long handle, int window, boolean force);
    private static native void nativeDpi(long handle, int dpi);
    private static native void nativeConfirmWindowState(long handle, int window, int requestSerial, boolean fullscreen);
    private static native void nativeConfirmMaximized(long handle, int window, int requestSerial, int axes);
    private static native void nativeText(long handle, int output, int window, String text);
    private static native void nativeData(long handle, int operation, int channel, int serial, int offer,
            int output, int window, int x, int y, String type, int descriptor);
    private static native void nativeDestroy(long handle);
}
