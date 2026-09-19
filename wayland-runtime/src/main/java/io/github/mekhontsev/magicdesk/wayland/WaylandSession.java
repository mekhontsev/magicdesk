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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WaylandSession implements AutoCloseable {
    static { System.loadLibrary("magicdesk_wayland_host"); }
    public record Window(long id, long parent, String title, String appId, boolean mapped, int width, int height) { }
    public interface Listener {
        void changed();
        void failed(long output, String message);
    }
    private interface Command { void run() throws RemoteException; }

    private final IWaylandServer server;
    private final int executorUid;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("WaylandRenderer");
    private final Handler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongSparseArray<Window> catalog = new LongSparseArray<>();
    private final LongSparseArray<Output> outputs = new LongSparseArray<>();
    private final LongSparseArray<Connection> connections = new LongSparseArray<>();
    private volatile List<Window> windows = List.of();
    private long nextOutput, nextConnection;
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
                if (removed) catalog.remove(id);
                else catalog.put(id, new Window(id, parent, title, appId, mapped, width, height));
                ArrayList<Window> snapshot = new ArrayList<>(catalog.size());
                for (int index = 0; index < catalog.size(); ++index) snapshot.add(catalog.valueAt(index));
                windows = List.copyOf(snapshot);
                main.post(listener::changed);
            });
        }
        @Override public void frame(long id, long serial, ParcelFileDescriptor pixels, int width, int height) {
            try { checkCaller(); }
            catch (RuntimeException error) { closeDescriptor(pixels); throw error; }
            if (!handler.post(() -> {
                Output output = outputs.get(id);
                if (closed.get() || output == null || output.released.get()) closeDescriptor(pixels);
                else output.receive(pixels, width, height);
                if (!closed.get() && serial != 0) remote(() -> server.frameConsumed(id, serial));
            })) closeDescriptor(pixels);
        }
        @Override public void failed(long output, String message) {
            checkCaller();
            WaylandSession.this.failed(output, message);
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
    };

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
    public boolean isClosed() { return closed.get(); }

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
        if (closed.get()) throw new IllegalStateException("Wayland session is closed");
        Output output = new Output(++nextOutput);
        handler.post(() -> {
            if (closed.get()) return;
            outputs.put(output.id, output);
            remote(() -> server.openOutput(output.id, window, width, height));
        });
        return output;
    }

    public void closeWindow(long window) {
        handler.post(() -> { if (!closed.get()) remote(() -> server.closeWindow(window)); });
    }

    private void remote(Command command) {
        try { command.run(); }
        catch (RemoteException | RuntimeException error) { failed(0, error.getMessage()); close(); }
    }

    private void failed(long output, String message) { main.post(() -> listener.failed(output, message)); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
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
            windows = List.of();
            try { server.stop(); } catch (RemoteException ignored) { }
            thread.quitSafely();
            main.post(listener::changed);
        });
    }

    public final class Output implements AutoCloseable {
        public final long id;
        private final AtomicBoolean released = new AtomicBoolean();
        private long surface;
        private ParcelFileDescriptor frame;
        private int frameWidth, frameHeight;

        private Output(long id) { this.id = id; }

        private void command(Command command) {
            handler.post(() -> { if (!closed.get() && !released.get()) remote(command); });
        }

        public void setSurface(Surface nextSurface, int width, int height) {
            if (released.get() || closed.get()) return;
            long next = nextSurface == null ? 0 : nativeAcquire(nextSurface);
            if (nextSurface != null && next == 0) { failed(id, "Cannot retain Android Surface"); return; }
            if (!handler.post(() -> {
                if (released.get() || closed.get()) { nativeRelease(next); return; }
                nativeRelease(surface);
                surface = next;
                if (surface == 0) remote(() -> server.focus(id, false));
                else {
                    remote(() -> server.resize(id, width, height));
                    if (frame != null && !nativePresent(surface, frame.getFd(), frameWidth, frameHeight))
                        failed(id, "Cannot present Wayland frame");
                }
            })) nativeRelease(next);
        }

        public void focus(boolean focused) { command(() -> server.focus(id, focused)); }
        public void pointer(double x, double y) { command(() -> server.pointer(id, x, y)); }
        public void button(int button, boolean down) { command(() -> server.button(id, button, down)); }
        public void scroll(double horizontal, double vertical) { command(() -> server.scroll(id, horizontal, vertical)); }
        public void key(int evdevCode, boolean down) { command(() -> server.key(id, evdevCode, down)); }

        private void receive(ParcelFileDescriptor pixels, int width, int height) {
            closeDescriptor(frame);
            frame = null;
            if (pixels == null) { nativeClear(surface); return; }
            if (!nativePresent(surface, pixels.getFd(), width, height)) {
                closeDescriptor(pixels);
                failed(id, "Invalid or unavailable Wayland frame");
                return;
            }
            frame = pixels; frameWidth = width; frameHeight = height;
        }

        private void release() {
            released.set(true);
            closeDescriptor(frame);
            frame = null;
            nativeRelease(surface);
            surface = 0;
        }

        @Override public void close() {
            if (!released.compareAndSet(false, true)) return;
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

    private static native long nativeAcquire(Surface surface);
    private static native void nativeRelease(long surface);
    private static native boolean nativePresent(long surface, int descriptor, int width, int height);
    private static native void nativeClear(long surface);
}