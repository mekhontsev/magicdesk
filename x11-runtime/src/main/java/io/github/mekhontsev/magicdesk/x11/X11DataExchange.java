package io.github.mekhontsev.magicdesk.x11;

import android.os.Handler;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Selection offers and XDND commands. No Android clipboard, URI, or Activity policy. */
public final class X11DataExchange implements AutoCloseable {
    public static final int CLIPBOARD = 0, DRAG = 1;
    public static final int ENABLE = 1, OFFER = 2, READ = 3, REQUEST = 4, REPLY = 5,
            ENTER = 6, MOVE = 7, LEAVE = 8, DROP = 9, STATUS = 10, FINISH = 11, CANCEL = 12, BEGIN = 13;
    public static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final long REQUEST_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REQUESTS = 16;

    public interface Source {
        List<String> types();
        /** An independently owned seekable descriptor. Null explicitly rejects the format. */
        ParcelFileDescriptor open(String type) throws IOException;
    }

    interface Sender {
        void send(int operation, int channel, int serial, int offer, int output, int window,
                int x, int y, String type, ParcelFileDescriptor descriptor);
    }

    public interface Listener {
        void onOffer(Offer offer);
        void onDragEvent(int operation, int output, boolean accepted);
    }

    public final class Offer implements Source {
        private final int channel, id, output, window;
        private final List<String> types;
        private Offer(int channel, int id, int output, int window, List<String> types) {
            this.channel = channel; this.id = id; this.output = output; this.window = window; this.types = types;
        }
        public int channel() { return channel; }
        public int id() { return id; }
        public int output() { return output; }
        public long window() { return Integer.toUnsignedLong(window); }
        @Override public List<String> types() { return types; }
        /** Only call from a worker, never the callback or renderer thread. */
        @Override public ParcelFileDescriptor open(String type) throws IOException {
            CompletableFuture<ParcelFileDescriptor> request = read(this, type);
            try { return request.get(); }
            catch (InterruptedException e) {
                discard(request);
                Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException();
            }
            catch (java.util.concurrent.ExecutionException e) { throw new IOException("X11 selection unavailable", e.getCause()); }
        }
    }

    private record Pending(int channel, int offer, CompletableFuture<ParcelFileDescriptor> result, Runnable timeout) { }
    private record Published(int id, Source source) { }
    private final Handler handler;
    private final Executor callbacks;
    private final Listener listener;
    private final Sender sender;
    private final ExecutorService io = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_REQUESTS), r -> new Thread(r, "X11Content"));
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private final Published[] published = new Published[2];
    private int sequence;
    private volatile long connection;
    private volatile boolean closed;

    X11DataExchange(Handler handler, Executor callbacks, Listener listener, Sender sender) {
        this.handler = handler; this.callbacks = callbacks; this.listener = listener; this.sender = sender;
    }

    public void clipboardActive(boolean active) { send(ENABLE, 0, 0, 0, 0, 0, active ? 1 : 0, 0, "", null); }

    public synchronized int publish(int channel, Source source) {
        if (closed) throw new IllegalStateException("X11 exchange closed");
        if (channel < 0 || channel > 1) throw new IllegalArgumentException("Invalid selection");
        List<String> types = validateTypes(source.types());
        int id = nextId();
        published[channel] = new Published(id, source);
        try (ParcelFileDescriptor fd = bytes(String.join("\n", types).getBytes(StandardCharsets.US_ASCII))) {
            send(OFFER, channel, 0, id, 0, 0, 0, 0, "", fd);
        } catch (IOException e) { throw new IllegalStateException("Cannot publish X11 offer", e); }
        return id;
    }

    public void drag(int operation, int offer, int output, long window, float x, float y, boolean sameServer) {
        if (operation < ENTER || operation > BEGIN || !Float.isFinite(x) || !Float.isFinite(y))
            throw new IllegalArgumentException("Invalid drag event");
        send(operation, DRAG, 0, offer, output, (int)window,
                operation == ENTER || operation == FINISH ? (sameServer ? 1 : 0) : Math.round(x * 10000),
                Math.round(y * 10000), "", null);
    }

    private synchronized int nextId() {
        if (sequence == Integer.MAX_VALUE) throw new IllegalStateException("X11 transfer identifiers exhausted");
        return ++sequence;
    }

    private synchronized CompletableFuture<ParcelFileDescriptor> read(Offer offer, String type) {
        CompletableFuture<ParcelFileDescriptor> result = new CompletableFuture<>();
        if (closed || !offer.types.contains(type) || pending.size() >= MAX_REQUESTS) {
            result.completeExceptionally(new IOException("Selection unavailable or too many pending transfers"));
            return result;
        }
        int id = nextId();
        Runnable timeout = () -> {
            synchronized (X11DataExchange.this) {
                Pending request = pending.remove(id);
                if (request != null) request.result.completeExceptionally(new IOException("X11 selection timed out"));
            }
        };
        pending.put(id, new Pending(offer.channel, offer.id, result, timeout));
        handler.postDelayed(timeout, REQUEST_TIMEOUT_MILLIS);
        send(READ, offer.channel, id, offer.id, 0, 0, 0, 0, type, null);
        return result;
    }

    private synchronized void discard(CompletableFuture<ParcelFileDescriptor> result) {
        pending.values().removeIf(request -> {
            if (request.result != result) return false;
            handler.removeCallbacks(request.timeout);
            return true;
        });
        result.thenAccept(X11DataExchange::closeFd);
        result.cancel(false);
    }

    void receive(int operation, int channel, int serial, int offer, int output, int window,
            int x, int y, String type, ParcelFileDescriptor fd) {
        trace("receive", operation, channel, serial, offer, output, window, x, y);
        if (closed || channel < 0 || channel > 1) { closeFd(fd); return; }
        if (operation == REPLY) {
            synchronized (this) {
                Pending request = pending.get(serial);
                if (request == null || request.channel != channel || request.offer != offer) { closeFd(fd); return; }
                pending.remove(serial);
                handler.removeCallbacks(request.timeout);
                if (fd == null) request.result.completeExceptionally(new IOException("X11 owner rejected " + type));
                else if (!request.result.complete(fd)) closeFd(fd);
            }
        } else if (operation == OFFER) {
            // Offer metadata is a bounded memfd; parse in wire order, not in competing workers.
            try (fd) {
                if (fd == null || fd.getStatSize() < 0 || fd.getStatSize() > 8192) return;
                byte[] bytes = new ParcelFileDescriptor.AutoCloseInputStream(fd).readNBytes(8193);
                if (bytes.length > 8192) throw new IOException("Oversized selection offer");
                List<String> types = validateTypes(bytes.length == 0 ? List.of()
                        : List.of(new String(bytes, StandardCharsets.US_ASCII).split("\n")));
                Offer value = new Offer(channel, offer, output, window, types);
                long epoch = connection;
                callbacks.execute(() -> { if (!closed && epoch == connection) listener.onOffer(value); });
            } catch (IOException | IllegalArgumentException e) {
                android.util.Log.w("X11Content", "Invalid selection offer", e);
            }
        } else if (operation == REQUEST) {
            closeFd(fd);
            Published value;
            synchronized (this) { value = published[channel]; }
            long epoch = connection;
            Runnable request = () -> {
                try (ParcelFileDescriptor data = value != null && value.id == offer && value.source.types().contains(type)
                        ? value.source.open(type) : null) {
                    if (epoch == connection) send(REPLY, channel, serial, offer, 0, 0, 0, 0, type, data);
                } catch (IOException | RuntimeException error) {
                    android.util.Log.w("X11Content", "Selection transfer failed", error);
                    if (epoch == connection) send(REPLY, channel, serial, offer, 0, 0, 0, 0, type, null);
                }
            };
            try { io.execute(request); }
            catch (RejectedExecutionException error) { send(REPLY, channel, serial, offer, 0, 0, 0, 0, type, null); }
        } else {
            closeFd(fd);
            long epoch = connection;
            callbacks.execute(() -> { if (!closed && epoch == connection) listener.onDragEvent(operation, output, x != 0); });
        }
    }

    private void send(int operation, int channel, int serial, int offer, int output, int window,
            int x, int y, String type, ParcelFileDescriptor descriptor) {
        trace("send", operation, channel, serial, offer, output, window, x, y);
        if (!closed) sender.send(operation, channel, serial, offer, output, window, x, y, type, descriptor);
    }

    private static void trace(String direction, int operation, int channel, int serial, int offer,
            int output, int window, int x, int y) {
        if (android.util.Log.isLoggable("X11Content", android.util.Log.DEBUG))
            android.util.Log.d("X11Content", direction + " op=" + operation + " channel=" + channel
                    + " serial=" + serial + " offer=" + offer + " output=" + output
                    + " window=" + window + " x=" + x + " y=" + y);
    }

    static List<String> validateTypes(List<String> values) {
        if (values == null || values.size() > 64) throw new IllegalArgumentException("Too many X11 formats");
        for (String value : values) {
            // X target names can contain quoted MIME parameters with spaces.
            // Reject framing/control bytes, not printable characters inside an opaque atom name.
            if (value == null || value.isEmpty() || value.length() >= 128 ||
                    !value.chars().allMatch(c -> c >= 32 && c < 127)) throw new IllegalArgumentException("Invalid X11 format");
        }
        return values.stream().distinct().toList();
    }

    public static ParcelFileDescriptor bytes(byte[] bytes) throws IOException {
        if (bytes.length > 1024 * 1024) throw new IOException("Inline selection exceeds 1 MiB");
        int fd = nativeBytes(bytes);
        if (fd < 0) throw new IOException("Cannot allocate selection descriptor");
        return ParcelFileDescriptor.adoptFd(fd);
    }

    private static void closeFd(ParcelFileDescriptor fd) { if (fd != null) try { fd.close(); } catch (IOException ignored) { } }

    synchronized void disconnected() {
        connection++;
        for (Pending request : pending.values()) {
            handler.removeCallbacks(request.timeout);
            request.result.completeExceptionally(new IOException("X11 session closed"));
        }
        pending.clear();
        published[0] = published[1] = null;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        disconnected();
        io.shutdownNow();
    }

    private static native int nativeBytes(byte[] bytes);
}
