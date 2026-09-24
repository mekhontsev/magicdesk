package io.github.mekhontsev.magicdesk.wayland;

import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import io.github.mekhontsev.magicdesk.hosted.HostedDataSource;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** wl_data_device offers. Android clipboard and URI grants belong to the host. */
public final class WaylandDataExchange implements AutoCloseable {
    public static final int CLIPBOARD = 0, DRAG = 1;
    public enum DragAction { BEGIN, ENTER, MOVE, LEAVE, DROP, FINISH, ABORT }
    public interface Listener { void offered(Offer offer); }
    private record Published(long id, HostedDataSource source) { }
    private record Pending(CompletableFuture<ParcelFileDescriptor> result, Runnable deadline) { }
    private final IWaylandServer server;
    private final Handler handler;
    private final Executor callbacks;
    private final Listener listener;
    private final Map<Long, Pending> requests = new LinkedHashMap<>();
    private final Published[] published = new Published[2];
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> new Thread(task, "WaylandContent"));
    private long sequence;
    private volatile boolean closed;

    WaylandDataExchange(IWaylandServer server, Handler handler, Executor callbacks, Listener listener) {
        this.server = server; this.handler = handler; this.callbacks = callbacks; this.listener = listener;
    }
    public final class Offer implements HostedDataSource {
        private final int channel;
        private final long id, output;
        private final List<String> types;
        private Offer(int channel, long id, long output, List<String> types) {
            this.channel = channel; this.id = id; this.output = output; this.types = types;
        }
        public int channel() { return channel; }
        public long id() { return id; }
        public long output() { return output; }
        @Override public List<String> types() { return types; }
        @Override public ParcelFileDescriptor open(String type) throws IOException {
            if (!types.contains(type)) throw new IOException("Format not offered");
            CompletableFuture<ParcelFileDescriptor> result = read(this, type);
            // EVENT_WAIT: native stream completion; handler deadline rejects missing replies.
            try { return result.get(); }
            catch (InterruptedException error) {
                discard(result); Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException();
            } catch (java.util.concurrent.ExecutionException error) { throw new IOException("Wayland content unavailable", error.getCause()); }
        }
    }
    public void active(boolean active) {
        if (closed) return;
        try { server.contentActive(active); } catch (RemoteException e) { close(); }
    }
    public void drag(long output, DragAction action, long offer, float x, float y, boolean accepted) {
        if (closed) return;
        try { server.drag(output, action.ordinal(), offer, x, y, accepted); }
        catch (RemoteException error) { close(); }
    }
    public synchronized long publish(int channel, HostedDataSource source) {
        if (closed || channel < 0 || channel > 1) throw new IllegalStateException("Content exchange unavailable");
        List<String> types = validate(source.types());
        long id = ++sequence;
        published[channel] = new Published(id, source);
        try { server.publishContent(channel, id, String.join("\n", types)); }
        catch (RemoteException error) { close(); throw new IllegalStateException("Wayland executor disconnected", error); }
        return id;
    }
    private synchronized CompletableFuture<ParcelFileDescriptor> read(Offer offer, String type) {
        var result = new CompletableFuture<ParcelFileDescriptor>();
        if (closed || requests.size() >= 16) {
            result.completeExceptionally(new IOException("Content exchange unavailable or busy")); return result;
        }
        long id = ++sequence;
        Runnable deadline = () -> {
            synchronized (this) {
                Pending pending = requests.remove(id);
                if (pending != null) pending.result.completeExceptionally(new IOException("Wayland content timed out"));
            }
        };
        requests.put(id, new Pending(result, deadline));
        // EVENT_WAIT: native callback/Binder delivery; timeout fails, never assumes completion.
        handler.postDelayed(deadline, 31_000);
        try { server.readContent(offer.channel, offer.id, id, type); }
        catch (RemoteException error) { close(); }
        return result;
    }
    private synchronized void discard(CompletableFuture<ParcelFileDescriptor> result) {
        requests.values().removeIf(pending -> {
            if (pending.result != result) return false;
            handler.removeCallbacks(pending.deadline); return true;
        });
        result.thenAccept(WaylandDataExchange::closeFd);
        result.cancel(false);
    }
    void offered(int channel, long id, long output, String types) {
        if (closed || channel < 0 || channel > 1 || types == null || types.length() >= 8192) return;
        Offer offer;
        try { offer = new Offer(channel, id, output, validate(types.isEmpty() ? List.of() : List.of(types.split("\n")))); }
        catch (IllegalArgumentException error) { return; }
        callbacks.execute(() -> { if (!closed) listener.offered(offer); });
    }
    synchronized void reply(long id, ParcelFileDescriptor fd) {
        Pending pending = requests.remove(id);
        if (pending == null) { closeFd(fd); return; }
        handler.removeCallbacks(pending.deadline);
        if (fd == null) pending.result.completeExceptionally(new IOException("Wayland producer rejected transfer"));
        else if (!pending.result.complete(fd)) closeFd(fd);
    }
    void requested(int channel, long id, long request, String type) {
        Published value;
        synchronized (this) { value = channel < 0 || channel > 1 ? null : published[channel]; }
        if (closed) return;
        Runnable work = () -> {
            try (ParcelFileDescriptor fd = value != null && value.id == id && value.source.types().contains(type)
                    ? value.source.open(type) : null) {
                send(request, fd);
            } catch (IOException | RuntimeException error) { send(request, null); }
        };
        try { workers.execute(work); }
        catch (RejectedExecutionException error) { send(request, null); }
    }
    private void send(long request, ParcelFileDescriptor fd) {
        if (closed) return;
        try { server.replyContent(request, fd); } catch (RemoteException error) { close(); }
    }
    static List<String> validate(List<String> types) {
        if (types == null || types.size() > 64) throw new IllegalArgumentException("Too many formats");
        for (String type : types) if (type == null || type.isEmpty() || type.length() >= 128 ||
                !type.chars().allMatch(c -> c >= 32 && c < 127)) throw new IllegalArgumentException("Invalid format");
        return types.stream().distinct().toList();
    }
    private static void closeFd(ParcelFileDescriptor fd) {
        if (fd != null) try { fd.close(); } catch (IOException ignored) { }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Pending pending : requests.values()) {
            handler.removeCallbacks(pending.deadline);
            pending.result.completeExceptionally(new IOException("Wayland session closed"));
        }
        requests.clear(); published[0] = published[1] = null; workers.shutdownNow();
    }
}
