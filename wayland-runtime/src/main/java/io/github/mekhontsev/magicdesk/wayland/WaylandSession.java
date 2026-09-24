package io.github.mekhontsev.magicdesk.wayland;
import io.github.mekhontsev.magicdesk.hosted.FramePresentation;
import io.github.mekhontsev.magicdesk.hosted.HostedFrame;
import io.github.mekhontsev.magicdesk.hosted.HostedFramePresenter;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture;

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
    public record Window(long id, long parent, String title, String appId, boolean mapped, int width, int height,
            HostedWindowConstraints constraints, long requestSerial, boolean fullscreen,
            long maximizeSerial, boolean maximized) { }
    public record Toplevel(long id, String title, String appId, boolean active, boolean maximized, boolean fullscreen, boolean minimized) { }
    public enum ToplevelAction { ACTIVATE, MAXIMIZE, FULLSCREEN, UNMAXIMIZE, UNFULLSCREEN, CLOSE, MINIMIZE, UNMINIMIZE }
    public interface Listener {
        void changed();
        void failed(long output, String message);
        default void frame(long output, int width, int height) { }
        default void geometryChanged(long window) { }
        default void textInputChanged(long output) { }
        default void windowGesture(long window, HostedWindowGesture gesture) { }
        default void contentOffer(WaylandDataExchange.Offer offer) { }
        default void dragEvent(long output, long offer, boolean finished, boolean accepted) { }
        default void cursor(long output, android.graphics.Bitmap image, int hotspotX, int hotspotY, boolean hidden) { }
    }
    public interface ShellListener {
        void changed();
        void closed(String reason);
        default void geometryChanged(long surface) { }
        default void toplevelAction(long id, ToplevelAction action) { }
    }
    private interface Command { void run() throws RemoteException; }

    private final IWaylandServer server;
    private final int executorUid;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("WaylandControl");
    private final Handler handler;
    private final WaylandDataExchange content;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongSparseArray<Window> catalog = new LongSparseArray<>();
    private final ConcurrentHashMap<Long, WaylandViewGeometry> geometries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, WaylandViewGeometry> dependentGeometries = new ConcurrentHashMap<>();
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
        @Override public void contentOffer(int channel, long id, long output, String types) {
            checkCaller(); handler.post(() -> content.offered(channel, id, output, types));
        }
        @Override public void contentRequest(int channel, long id, long request, String type) {
            checkCaller(); handler.post(() -> content.requested(channel, id, request, type));
        }
        @Override public void dragEvent(long output, long offer, boolean finished, boolean accepted) {
            checkCaller();
            main.post(() -> { if (!closed.get()) listener.dragEvent(output, offer, finished, accepted); });
        }
        @Override public void contentReply(long request, ParcelFileDescriptor fd) {
            try { checkCaller(); }
            catch (RuntimeException error) { closeDescriptor(fd); throw error; }
            if (!handler.post(() -> content.reply(request, fd))) closeDescriptor(fd);
        }
        @Override public void textInput(long id, long editor, long revision, byte[] surrounding,
                int cursor, int anchor, int purpose, int hints, boolean caretValid,
                float left, float top, float right, float bottom, boolean inputMethodChange) {
            checkCaller();
            var context = WaylandText.state(editor, revision, surrounding, cursor, anchor, purpose, hints);
            var state = context == null ? null : new io.github.mekhontsev.magicdesk.hosted.HostedTextState(
                    context.editor(), context.revision(), context.purpose(), context.hints(), context.surrounding(),
                    context.cursor(), context.anchor(), caretValid
                    ? new io.github.mekhontsev.magicdesk.hosted.HostedTextState.Caret(left, top, right, bottom) : null,
                    inputMethodChange);
            handler.post(() -> {
                Output output = outputs.get(id);
                if (closed.get() || output == null || output.released.get()
                        || java.util.Objects.equals(output.textState, state)) return;
                output.textState = state;
                main.post(() -> { if (!closed.get() && !output.released.get()) listener.textInputChanged(id); });
            });
        }
        @Override public void cursor(long id, int[] pixels, int width, int height, int x, int y, boolean hidden) {
            checkCaller();
            if (pixels != null && (width < 1 || height < 1 || width > 256 || height > 256 || pixels.length != width * height))
                throw new IllegalArgumentException("Invalid Wayland cursor image");
            handler.post(() -> {
                Output output = outputs.get(id);
                if (closed.get() || output == null || output.released.get()) return;
                android.graphics.Bitmap bitmap = pixels == null ? null : android.graphics.Bitmap.createBitmap(
                        pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888);
                main.post(() -> {
                    if (!closed.get() && !output.released.get()) listener.cursor(id, bitmap, x, y, hidden);
                });
            });
        }
        @Override public void window(long id, long parent, String title, String appId, boolean mapped,
                int width, int height, int minWidth, int minHeight, int maxWidth, int maxHeight,
                long requestSerial, boolean fullscreen, long maximizeSerial, boolean maximized, boolean removed) {
            checkCaller();
            handler.post(() -> {
                if (closed.get()) return;
                if (removed) {
                    catalog.remove(id);
                    geometries.remove(id);
                    dependentGeometries.remove(id);
                    releaseSurfaceOutputs(id);
                }
                else catalog.put(id, new Window(id, parent, title, appId, mapped, width, height,
                        new HostedWindowConstraints(minWidth, minHeight, maxWidth, maxHeight), requestSerial, fullscreen,
                        maximizeSerial, maximized));
                ArrayList<Window> snapshot = new ArrayList<>(catalog.size());
                for (int index = 0; index < catalog.size(); ++index) snapshot.add(catalog.valueAt(index));
                windows = List.copyOf(snapshot);
                main.post(listener::changed);
            });
        }
        @Override public void windowGesture(long window, int edges) {
            checkCaller();
            HostedWindowGesture gesture = switch (edges) {
                case 0 -> HostedWindowGesture.MOVE;
                case 1 -> HostedWindowGesture.NORTH;
                case 2 -> HostedWindowGesture.SOUTH;
                case 4 -> HostedWindowGesture.WEST;
                case 8 -> HostedWindowGesture.EAST;
                case 5 -> HostedWindowGesture.NORTH_WEST;
                case 9 -> HostedWindowGesture.NORTH_EAST;
                case 6 -> HostedWindowGesture.SOUTH_WEST;
                case 10 -> HostedWindowGesture.SOUTH_EAST;
                default -> throw new IllegalArgumentException("Invalid Wayland resize edge");
            };
            main.post(() -> { if (!closed.get()) listener.windowGesture(window, gesture); });
        }
        @Override public void frame(long id, long serial, long generation, HostedFrame frame) {
            int width = frame == null ? 0 : frame.width, height = frame == null ? 0 : frame.height;
            try { checkCaller(); }
            catch (RuntimeException error) { HostedFrame.close(frame); throw error; }
            if (!handler.post(() -> {
                Output output = outputs.get(id);
                if (closed.get() || output == null || !output.acceptsFrame(generation)) {
                    HostedFrame.close(frame);
                    frameConsumed(id, serial);
                } else {
                    output.presenter.present(frame, generation, submitted -> handler.post(() -> {
                        frameConsumed(id, serial);
                        if (closed.get() || !output.acceptsFrame(generation)) return;
                        if (!submitted) {
                            String message = "Wayland frame was not submitted to the Android Surface";
                            output.presentation.fail(generation, new IOException(message));
                            if (frame != null) output.failed(message, generation);
                            return;
                        }
                        if (output.width != width || output.height != height) {
                            output.width = width; output.height = height;
                            main.post(() -> {
                                if (!closed.get() && output.acceptsFrame(generation))
                                    listener.frame(id, width, height);
                            });
                        }
                        output.presentation.submitted(generation);
                    }));
                }
            })) HostedFrame.close(frame);
        }
        @Override public void failed(long output, long generation, String message) {
            checkCaller();
            handler.post(() -> {
                Output current = outputs.get(output);
                if (generation != 0 && (current == null || !current.acceptsFrame(generation))) return;
                if (current != null) {
                    if (generation == 0) {
                        current.release();
                        outputs.remove(output);
                        if (!closed.get()) remote(() -> server.releaseOutput(output));
                    }
                    else current.presentation.fail(generation, new IOException(message));
                }
                if (current != null) current.failed(message, generation == 0 ? current.presentation.generation() : generation);
                else WaylandSession.this.failed(output, message);
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
                publishShellSurface(binding, binding.catalog.snapshot());
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
        @Override public void toplevelAction(long owner, long id, int action) {
            checkCaller();
            if (action < 0 || action >= ToplevelAction.values().length) return;
            ShellBinding binding = shellBinding;
            main.post(() -> {
                if (!closed.get() && binding != null && !binding.released.get() && binding.id == owner)
                    binding.listener.toplevelAction(id, ToplevelAction.values()[action]);
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
                    var selected = geometry.dependents() ? dependentGeometries : geometries;
                    if (catalog.get(id) == null || !updateGeometry(selected, geometry)) return;
                    main.post(() -> {
                        if (closed.get() || !selected.containsKey(id)) return;
                        listener.geometryChanged(id);
                    });
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

    private void publishShellSurface(ShellBinding binding, List<WaylandShellSurface> committed) {
        main.post(() -> {
            if (binding.released.get() || closed.get()) return;
            // Publish with its callback so a fast unmap/remap cannot replace an unobserved lifecycle state.
            binding.surfaces = committed;
            binding.listener.changed();
        });
    }

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
        content = new WaylandDataExchange(server, handler, main::post, listener::contentOffer);
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
    public WaylandViewGeometry dependentGeometry(long window) { return closed.get() ? null : dependentGeometries.get(window); }
    public boolean isClosed() { return closed.get(); }
    public WaylandDataExchange content() { return content; }
    public IWaylandServer contentFiles() { return server; }

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
        return openOutput(window, binding, null, null, width, height);
    }

    private synchronized Output openOutput(long window, ShellBinding binding, Output parent,
            java.util.function.Consumer<Throwable> failure, int width, int height) {
        if (closed.get()) throw new IllegalStateException("Wayland session is closed");
        if (binding != null && binding.released.get()) throw new IllegalStateException("Wayland shell binding is closed");
        Output output = new Output(++nextOutput, window, binding, parent, failure);
        if (!handler.post(() -> {
            if (closed.get() || (binding != null && binding.released.get())) { output.release(); return; }
            outputs.put(output.id, output);
            remote(() -> server.openOutput(output.id, window, binding == null ? 0 : binding.id,
                    parent == null ? 0 : parent.id, width, height));
        })) output.release();
        return output;
    }

    public void closeWindow(long window) {
        closeWindow(window, false);
    }

    public void closeWindow(long window, boolean force) {
        handler.post(() -> { if (!closed.get()) remote(() -> server.closeWindow(window, force)); });
    }

    public void confirmFullscreen(long window, long serial, boolean fullscreen) {
        handler.post(() -> { if (!closed.get()) remote(() -> server.confirmFullscreen(window, serial, fullscreen)); });
    }
    public void confirmMaximized(long window, long serial, boolean maximized) {
        handler.post(() -> { if (!closed.get()) remote(() -> server.confirmMaximized(window, serial, maximized)); });
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
            content.close();
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
            dependentGeometries.clear();
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

        public void publishToplevel(Toplevel window, boolean removed) {
            java.util.Objects.requireNonNull(window);
            if (window.id() <= 0 || window.title() == null || window.appId() == null)
                throw new IllegalArgumentException("Invalid toplevel identity");
            handler.post(() -> {
                if (!released.get() && !closed.get()) remote(() -> server.publishToplevel(id, window.id(),
                        window.title(), window.appId(), window.active(), window.maximized(), window.fullscreen(), window.minimized(), removed));
            });
        }

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
        private final Output parent;
        private volatile Output dependents;
        private final java.util.function.Consumer<Throwable> failureListener;
        private int width, height;
        private volatile io.github.mekhontsev.magicdesk.hosted.HostedTextState textState;
        private final AtomicBoolean released = new AtomicBoolean();
        private final HostedFramePresenter presenter;
        private final FramePresentation presentation = new FramePresentation();

        private Output(long id, long window, ShellBinding binding, Output parent,
                java.util.function.Consumer<Throwable> failure) {
            this.id = id;
            this.window = window;
            this.binding = binding;
            this.parent = parent;
            failureListener = failure;
            presenter = new HostedFramePresenter(id);
        }

        public Output borrowDependents(java.util.function.Consumer<Throwable> failed) {
            synchronized (WaylandSession.this) {
                if (released.get() || closed.get() || binding != null || parent != null || dependents != null)
                    throw new IllegalStateException("Wayland family cannot be borrowed");
                dependents = openOutput(window, null, this, java.util.Objects.requireNonNull(failed), 1, 1);
                return dependents;
            }
        }

        private void failed(String message) {
            failed(message, presentation.generation());
        }

        private void failed(String message, long generation) {
            main.post(() -> {
                if (!presentation.accepts(generation)) return;
                if (failureListener != null) failureListener.accept(new IOException(message));
                else listener.failed(id, message);
            });
        }

        private boolean acceptsFrame(long generation) {
            return !released.get() && presentation.accepts(generation);
        }

        private void command(Command command) {
            handler.post(() -> { if (!closed.get() && !released.get()) remote(command); });
        }

        public void setSurface(Surface nextSurface, int width, int height) {
            try { setSurface(nextSurface, nextSurface == null ? null : new WaylandViewport(0, 0, width, height), parent == null); }
            catch (IllegalArgumentException error) { failed(error.getMessage()); }
        }

        /** Completes after a matching frame is queued to this Surface, not after display scanout. */
        public CompletableFuture<Void> setSurface(Surface surface, WaylandViewport viewport) {
            return setSurface(java.util.Objects.requireNonNull(surface), java.util.Objects.requireNonNull(viewport), false);
        }

        private CompletableFuture<Void> setSurface(Surface surface, WaylandViewport viewport, boolean configureClient) {
            // Revoke old frames on the caller thread, before Android can destroy their Surface.
            CompletableFuture<Void> completion = new CompletableFuture<>();
            long generation = presentation.begin(completion);
            final HostedFramePresenter.SurfaceLease lease;
            try { lease = new HostedFramePresenter.SurfaceLease(surface); }
            catch (IOException | RuntimeException error) {
                presentation.fail(generation, error);
                failed(error.getMessage(), generation);
                return completion;
            }
            if (!handler.post(() -> {
                if (closed.get() || !acceptsFrame(generation)) {
                    lease.close();
                    completion.completeExceptionally(new IOException("Wayland output is closed"));
                    return;
                }
                width = height = 0;
                if (!presenter.setSurface(lease, generation)) {
                    String message = "Cannot attach Android Surface";
                    presentation.fail(generation, new IOException(message));
                    failed(message, generation);
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
                presentation.fail(generation, new IOException("Wayland session is closed"));
            }
            return completion;
        }

        public void focus(boolean focused) { command(() -> server.focus(id, focused)); }
        public void scale(double scale) { command(() -> server.scale(id, scale)); }
        public void pointer(double x, double y) { command(() -> server.pointer(id, x, y)); }
        public void button(int button, boolean down) { command(() -> server.button(id, button, down)); }
        public void scroll(double horizontal, double vertical) { command(() -> server.scroll(id, horizontal, vertical)); }
        public void key(int evdevCode, boolean down) { key(0, evdevCode, down); }
        public void key(int androidKey, int scanCode, boolean down) {
            command(() -> server.key(id, androidKey, scanCode, down));
        }
        public io.github.mekhontsev.magicdesk.hosted.HostedTextState textState() { return released.get() ? null : textState; }
        public boolean supportsText() { return textState() != null; }
        public void text(io.github.mekhontsev.magicdesk.hosted.HostedTextState state, String text, boolean composing, int cursor) {
            if (text == null || text.indexOf('\0') >= 0 || cursor < 0 || cursor > text.length())
                throw new IllegalArgumentException("Invalid text edit");
            if (state != null) command(() -> WaylandText.send(text, composing, cursor,
                    edit -> remote(() -> server.text(id, state.editor(), edit.text(), edit.composing(), edit.cursor()))));
        }
        public boolean deleteText(io.github.mekhontsev.magicdesk.hosted.HostedTextState state,
                int before, int after, boolean codePoints, String preedit, int cursor) {
            var edit = WaylandText.deletion(state, before, after, codePoints);
            if (edit == null) return false;
            WaylandText.send(preedit, true, cursor, preview -> command(() -> server.deleteText(
                    id, state.editor(), state.revision(), edit.before(), edit.after(), preview.text(), preview.cursor())));
            return true;
        }

        private void release() {
            released.set(true);
            unlinkFamily();
            presentation.invalidate("Wayland output released");
            presenter.close();
        }

        private void unlinkFamily() {
            Output child;
            synchronized (WaylandSession.this) {
                child = dependents;
                dependents = null;
                if (parent != null && parent.dependents == this) parent.dependents = null;
            }
            if (child != null) child.close();
        }

        @Override public void close() {
            if (!released.compareAndSet(false, true)) return;
            presentation.invalidate("Wayland output released");
            unlinkFamily();
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
