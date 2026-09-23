package io.github.mekhontsev.magicdesk.wayland;

import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.LongSparseArray;
import android.view.Surface;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WaylandSession implements AutoCloseable {
    public record Window(long id, long parent, String title, String appId, boolean mapped, int width, int height) { }
    public interface Listener {
        void changed();
        void failed(long output, String message);
        default void frame(long output, int width, int height) { }
        default void geometryChanged(long window) { }
    }
    public interface ShellListener {
        void changed();
        void closed(String reason);
        default void geometryChanged(long surface) { }
    }
    private interface Command { void run() throws RemoteException; }

    private final IWaylandServer server;
    private final int executorUid;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("WaylandControl");
    private final Handler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongSparseArray<Window> catalog = new LongSparseArray<>();
    private final ConcurrentHashMap<Long, WaylandViewGeometry> geometries = new ConcurrentHashMap<>();
    private final LongSparseArray<Output> outputs = new LongSparseArray<>();
    private final LongSparseArray<Connection> connections = new LongSparseArray<>();
    private volatile List<Window> windows = List.of();
    private long nextOutput, nextConnection, nextShell;
    private volatile ShellBinding shellBinding;
    private final IBinder.DeathRecipient serverDied = () -> {
        failed(0, "Wayland executor disconnected");
        close();
    };

    private final IWaylandEvents events = new IWaylandEvents.Stub() {
        private void checkCaller() {
            if (Binder.getCallingUid() != executorUid) throw new SecurityException("Not the Wayland executor UID");
        }
        @Override public void window(long id, long parent, String title, String appId, boolean mapped,
                int width, int height, boolean removed) {
            checkCaller();
            handler.post(() -> {
                if (closed.get()) return;
                if (removed) {
                    catalog.remove(id);
                    geometries.remove(id);
                    releaseSurfaceOutputs(id);
                }
                else catalog.put(id, new Window(id, parent, title, appId, mapped, width, height));
                ArrayList<Window> snapshot = new ArrayList<>(catalog.size());
                for (int index = 0; index < catalog.size(); ++index) snapshot.add(catalog.valueAt(index));
                windows = List.copyOf(snapshot);
                main.post(listener::changed);
            });
        }
        @Override public void frame(long id, long serial, long generation, ParcelFileDescriptor pixels, int width, int height) {
            try { checkCaller(); }
            catch (RuntimeException error) { closeDescriptor(pixels); throw error; }
            if (!handler.post(() -> {
                Output output = outputs.get(id);
                if (closed.get() || output == null || output.released.get() || !output.presentation.accepts(generation)) {
                    closeDescriptor(pixels);
                    frameConsumed(id, serial);
                } else {
                    output.presenter.present(pixels, width, height, generation, submitted -> handler.post(() -> {
                        frameConsumed(id, serial);
                        if (closed.get() || output.released.get() || !output.presentation.accepts(generation)) return;
                        if (!submitted) {
                            String message = "Wayland frame was not submitted to the Android Surface";
                            output.presentation.fail(new IOException(message));
                            if (pixels != null) WaylandSession.this.failed(id, message);
                            return;
                        }
                        if (output.width != width || output.height != height) {
                            output.width = width; output.height = height;
                            main.post(() -> {
                                if (!closed.get() && !output.released.get() && output.presentation.accepts(generation))
                                    listener.frame(id, width, height);
                            });
                        }
                        output.presentation.submitted(generation);
                    }));
                }
            })) closeDescriptor(pixels);
        }
        @Override public void failed(long output, long generation, String message) {
            checkCaller();
            handler.post(() -> {
                Output current = outputs.get(output);
                if (generation != 0 && (current == null || !current.presentation.accepts(generation))) return;
                if (current != null) {
                    if (generation == 0) {
                        current.release();
                        outputs.remove(output);
                        if (!closed.get()) remote(() -> server.releaseOutput(output));
                    }
                    else current.presentation.fail(new IOException(message));
                }
                WaylandSession.this.failed(output, message);
            });
        }
        @Override public void client(long request, ParcelFileDescriptor descriptor, String error) {
            try { checkCaller(); }
            catch (RuntimeException failure) { closeDescriptor(descriptor); throw failure; }
            if (!handler.post(() -> {
                Connection connection = connections.get(request);
                if (closed.get() || connection == null) { closeDescriptor(descriptor); return; }
                connections.remove(request);
                handler.removeCallbacks(connection.deadline);
                if (descriptor == null || error == null || !error.isEmpty()) {
                    closeDescriptor(descriptor);
                    connection.result.completeExceptionally(new IOException(error == null || error.isEmpty()
                            ? "Missing Wayland client connection" : error));
                } else if (!connection.result.complete(descriptor)) closeDescriptor(descriptor);
            })) closeDescriptor(descriptor);
        }
        @Override public void shellSurface(long owner, long id, WaylandShellSurface surface) {
            checkCaller();
            handler.post(() -> {
                ShellBinding binding = shellBinding;
                if (closed.get() || binding == null || binding.released.get() || binding.id != owner) return;
                if (!binding.catalog.update(owner, id, surface)) return;
                if (surface == null) { binding.geometries.remove(id); releaseSurfaceOutputs(id); }
                binding.surfaces = binding.catalog.snapshot();
                main.post(() -> { if (!binding.released.get() && !closed.get()) binding.listener.changed(); });
            });
        }
        @Override public void shellOutput(long owner, int width, int height, String error) {
            checkCaller();
            handler.post(() -> {
                ShellBinding binding = shellBinding;
                if (closed.get() || binding == null || binding.released.get() || binding.id != owner) return;
                if (error == null || !error.isEmpty()) {
                    binding.release(error == null ? "Invalid Wayland shell output response" : error);
                } else {
                    binding.ready.complete(null);
                }
            });
        }
        @Override public void geometry(long owner, WaylandViewGeometry geometry) {
            checkCaller();
            java.util.Objects.requireNonNull(geometry);
            handler.post(() -> {
                if (closed.get()) return;
                long id = geometry.id();
                ShellBinding binding = shellBinding;
                if (owner == 0) {
                    if (catalog.get(id) == null || !updateGeometry(geometries, geometry)) return;
                    main.post(() -> { if (!closed.get() && geometries.containsKey(id)) listener.geometryChanged(id); });
                } else {
                    if (binding == null || binding.released.get() || binding.id != owner
                            || !binding.catalog.contains(id) || !updateGeometry(binding.geometries, geometry)) return;
                    main.post(() -> {
                        if (!closed.get() && !binding.released.get() && binding.geometries.containsKey(id))
                            binding.listener.geometryChanged(id);
                    });
                }
            });
        }
    };

    private static boolean updateGeometry(ConcurrentHashMap<Long, WaylandViewGeometry> catalog, WaylandViewGeometry next) {
        WaylandViewGeometry previous = catalog.get(next.id());
        if (previous != null && previous.revision() >= next.revision()) return false;
        catalog.put(next.id(), next);
        return true;
    }

    public WaylandSession(IWaylandServer server, int executorUid, Listener listener) throws RemoteException {
        this.server = java.util.Objects.requireNonNull(server);
        this.executorUid = executorUid;
        this.listener = java.util.Objects.requireNonNull(listener);
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            server.asBinder().linkToDeath(serverDied, 0);
            server.retain(events);
        } catch (RemoteException | RuntimeException error) {
            server.asBinder().unlinkToDeath(serverDied, 0);
            thread.quitSafely();
            throw error;
        }
    }

    public List<Window> windows() { return windows; }
    public WaylandViewGeometry geometry(long window) { return closed.get() ? null : geometries.get(window); }
    public boolean isClosed() { return closed.get(); }

    /** Explicit admission, independent of Android display placement or Desktop startup. */
    public synchronized ShellBinding bindShell(int width, int height, ShellListener listener) {
        checkShellDimensions(width, height);
        if (closed.get()) throw new IllegalStateException("Wayland session is closed");
        if (shellBinding != null) throw new IllegalStateException("Wayland shell output already bound");
        ShellBinding binding = new ShellBinding(++nextShell, java.util.Objects.requireNonNull(listener));
        shellBinding = binding;
        if (!handler.post(() -> {
            if (closed.get() || binding.released.get()) return;
            binding.catalog.acquire(binding.id);
            remote(() -> server.setShellOutput(binding.id, width, height));
        })) binding.release("Wayland session is closed");
        return binding;
    }

    private static void checkShellDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384)
            throw new IllegalArgumentException("Invalid Wayland shell output dimensions");
    }

    public CompletableFuture<ParcelFileDescriptor> connect() {
        CompletableFuture<ParcelFileDescriptor> result = new CompletableFuture<>();
        if (!handler.post(() -> {
            if (closed.get() || connections.size() >= 16) {
                result.completeExceptionally(new IOException("Wayland session closed or connection queue full"));
                return;
            }
            if (result.isDone()) return;
            Connection connection = new Connection(++nextConnection, result);
            connections.put(connection.id, connection);
            handler.postDelayed(connection.deadline, 10_000);
            result.whenComplete((descriptor, error) -> handler.post(() -> {
                if (connections.get(connection.id) == connection) {
                    connections.remove(connection.id);
                    handler.removeCallbacks(connection.deadline);
                }
            }));
            remote(() -> server.openClient(connection.id));
        })) result.completeExceptionally(new IOException("Wayland session is closed"));
        return result;
    }

    private final class Connection {
        final long id;
        final CompletableFuture<ParcelFileDescriptor> result;
        final Runnable deadline = this::expire;

        Connection(long id, CompletableFuture<ParcelFileDescriptor> result) {
            this.id = id;
            this.result = result;
        }

        void expire() {
            if (connections.get(id) != this) return;
            connections.remove(id);
            result.completeExceptionally(new TimeoutException("Wayland client connection deadline expired"));
        }
    }

    public synchronized Output openOutput(long window, int width, int height) {
        return openOutput(window, null, width, height);
    }

    private synchronized Output openOutput(long window, ShellBinding binding, int width, int height) {
        if (closed.get()) throw new IllegalStateException("Wayland session is closed");
        if (binding != null && binding.released.get()) throw new IllegalStateException("Wayland shell binding is closed");
        Output output = new Output(++nextOutput, window, binding);
        if (!handler.post(() -> {
            if (closed.get() || (binding != null && binding.released.get())) { output.release(); return; }
            outputs.put(output.id, output);
            remote(() -> server.openOutput(output.id, window, binding == null ? 0 : binding.id, width, height));
        })) output.release();
        return output;
    }

    public void closeWindow(long window) {
        closeWindow(window, false);
    }

    public void closeWindow(long window, boolean force) {
        handler.post(() -> { if (!closed.get()) remote(() -> server.closeWindow(window, force)); });
    }

    private void frameConsumed(long id, long serial) {
        if (!closed.get() && serial != 0) remote(() -> server.frameConsumed(id, serial));
    }

    private void remote(Command command) {
        try { command.run(); }
        catch (RemoteException | RuntimeException error) { failed(0, error.getMessage()); close(); }
    }

    private void failed(long output, String message) { main.post(() -> listener.failed(output, message)); }

    private void releaseSurfaceOutputs(long id) {
        for (int index = outputs.size() - 1; index >= 0; --index) {
            Output output = outputs.valueAt(index);
            if (output.window != id) continue;
            output.release();
            outputs.removeAt(index);
        }
    }

    @Override public void close() {
        final ShellBinding binding;
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            binding = shellBinding;
        }
        if (binding != null) binding.release("Wayland session is closed");
        server.asBinder().unlinkToDeath(serverDied, 0);
        handler.post(() -> {
            for (int index = 0; index < connections.size(); ++index) {
                Connection connection = connections.valueAt(index);
                handler.removeCallbacks(connection.deadline);
                connection.result.completeExceptionally(new IOException("Wayland session is closed"));
            }
            connections.clear();
            for (int index = 0; index < outputs.size(); ++index) outputs.valueAt(index).release();
            outputs.clear();
            catalog.clear();
            geometries.clear();
            windows = List.of();
            try { server.stop(); } catch (RemoteException ignored) { }
            thread.quitSafely();
            main.post(listener::changed);
        });
    }

    public final class ShellBinding implements AutoCloseable {
        private final long id;
        private final ShellListener listener;
        private final ShellSurfaceCatalog catalog = new ShellSurfaceCatalog();
        private final AtomicBoolean released = new AtomicBoolean();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private volatile List<WaylandShellSurface> surfaces = List.of();
        private final ConcurrentHashMap<Long, WaylandViewGeometry> geometries = new ConcurrentHashMap<>();

        private ShellBinding(long id, ShellListener listener) { this.id = id; this.listener = listener; }

        public List<WaylandShellSurface> surfaces() { return released.get() ? List.of() : surfaces; }
        public WaylandViewGeometry geometry(long surface) { return released.get() ? null : geometries.get(surface); }
        public boolean isClosed() { return released.get(); }
        public CompletableFuture<Void> ready() { return ready.copy(); }

        public void resize(int width, int height) {
            checkShellDimensions(width, height);
            handler.post(() -> {
                if (!released.get() && !closed.get()) remote(() -> server.setShellOutput(id, width, height));
            });
        }

        public void configure(long surface, long revision, int x, int y, int width, int height) {
            handler.post(() -> {
                if (!released.get() && !closed.get() && catalog.accepts(id, surface, revision))
                    remote(() -> server.configureShell(id, surface, revision, x, y, width, height));
            });
        }

        public Output openOutput(long surface, int width, int height) {
            return WaylandSession.this.openOutput(surface, this, width, height);
        }

        private void release(String reason) {
            synchronized (WaylandSession.this) {
                if (!released.compareAndSet(false, true)) return;
                if (shellBinding == this) shellBinding = null;
                surfaces = List.of();
                // Queue revocation before allowing a replacement owner to enqueue admission.
                handler.post(() -> {
                    catalog.release(id);
                    geometries.clear();
                    for (int index = outputs.size() - 1; index >= 0; --index) {
                        Output output = outputs.valueAt(index);
                        if (output.binding != this) continue;
                        output.release();
                        outputs.removeAt(index);
                    }
                    if (!closed.get()) remote(() -> server.releaseShell(id));
                });
            }
            ready.completeExceptionally(new IOException(reason.isEmpty() ? "Wayland shell binding is closed" : reason));
            main.post(() -> listener.closed(reason));
        }

        @Override public void close() { release(""); }
    }

    public final class Output implements AutoCloseable {
        public final long id;
        private final long window;
        private final ShellBinding binding;
        private int width, height;
        private final AtomicBoolean released = new AtomicBoolean();
        private final WaylandFramePresenter presenter;
        private final FramePresentation presentation = new FramePresentation();

        private Output(long id, long window, ShellBinding binding) {
            this.id = id;
            this.window = window;
            this.binding = binding;
            presenter = new WaylandFramePresenter(id);
        }

        private void command(Command command) {
            handler.post(() -> { if (!closed.get() && !released.get()) remote(command); });
        }

        public void setSurface(Surface nextSurface, int width, int height) {
            try { setSurface(nextSurface, nextSurface == null ? null : new WaylandViewport(0, 0, width, height), true); }
            catch (IllegalArgumentException error) { failed(id, error.getMessage()); }
        }

        /** Completes after a matching frame is queued to this Surface, not after display scanout. */
        public CompletableFuture<Void> setSurface(Surface surface, WaylandViewport viewport) {
            return setSurface(java.util.Objects.requireNonNull(surface), java.util.Objects.requireNonNull(viewport), false);
        }

        private CompletableFuture<Void> setSurface(Surface surface, WaylandViewport viewport, boolean configureClient) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            final WaylandFramePresenter.SurfaceLease lease;
            try { lease = new WaylandFramePresenter.SurfaceLease(surface); }
            catch (IOException | RuntimeException error) {
                completion.completeExceptionally(error);
                failed(id, error.getMessage());
                return completion;
            }
            if (!handler.post(() -> {
                if (closed.get() || released.get()) {
                    lease.close();
                    completion.completeExceptionally(new IOException("Wayland output is closed"));
                    return;
                }
                long generation = presentation.begin(completion);
                width = height = 0;
                if (!presenter.setSurface(lease, generation)) {
                    String message = "Cannot attach Android Surface";
                    presentation.fail(new IOException(message));
                    failed(id, message);
                    return;
                }
                remote(() -> {
                    if (surface != null) server.viewport(id, generation, viewport.x(), viewport.y(),
                            viewport.width(), viewport.height(), configureClient);
                    server.setVisible(id, surface != null);
                });
                if (surface == null) presentation.submitted(generation);
            })) {
                lease.close();
                completion.completeExceptionally(new IOException("Wayland session is closed"));
            }
            return completion;
        }

        public void focus(boolean focused) { command(() -> server.focus(id, focused)); }
        public void pointer(double x, double y) { command(() -> server.pointer(id, x, y)); }
        public void button(int button, boolean down) { command(() -> server.button(id, button, down)); }
        public void scroll(double horizontal, double vertical) { command(() -> server.scroll(id, horizontal, vertical)); }
        public void key(int evdevCode, boolean down) { key(0, evdevCode, down); }
        public void key(int androidKey, int scanCode, boolean down) {
            command(() -> server.key(id, androidKey, scanCode, down));
        }

        private void release() {
            released.set(true);
            presentation.invalidate("Wayland output released");
            presenter.close();
        }

        @Override public void close() {
            if (!released.compareAndSet(false, true)) return;
            presenter.close();
            handler.post(() -> {
                release();
                outputs.remove(id);
                if (!closed.get()) remote(() -> server.releaseOutput(id));
            });
        }
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
    }

}
