package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One guest process generation. A lost connection invalidates handles rather than reconnecting to another bus. */
final class LinuxBusConnection implements Closeable {
    final String id = "linux-" + java.util.UUID.randomUUID();
    private final ExecutorSocketChannel channel;
    private final DataInputStream input;
    private final DataOutputStream output;
    private JSONObject context;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock command = new ReentrantLock();
    private final ConcurrentHashMap<Integer, CompletableFuture<LinuxBusWire.Message>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> hello = new CompletableFuture<>();
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    private final ArrayDeque<Integer> eventSizes = new ArrayDeque<>();
    private int eventBytes;
    private CompletableFuture<Long> changed = new CompletableFuture<>();
    private long generation, discarded;
    private int serial;
    private final Runnable disconnected;
    private final OperationResources resources = new OperationResources();

    LinuxBusConnection(ParcelFileDescriptor socket, byte[] token, Runnable disconnected) throws IOException {
        this.disconnected = disconnected;
        var admission = new DataInputStream(new FileInputStream(socket.getFileDescriptor()));
        byte[] supplied = new byte[token.length]; admission.readFully(supplied);
        if (!java.security.MessageDigest.isEqual(token, supplied)) throw new IOException("Invalid Linux automation admission");
        channel = new ExecutorSocketChannel(socket);
        input = new DataInputStream(channel.input);
        output = new DataOutputStream(channel.output);
        try {
            output.writeByte(0); output.flush();
            new Thread(this::read, "Linux automation events").start();
        } catch (IOException | RuntimeException error) { channel.close(); throw error; }
    }

    boolean alive() { return !closed.get() && hello.isDone() && !hello.isCompletedExceptionally(); }
    void awaitReady() throws IOException { await(hello, 5000); }
    JSONObject context() throws IOException, JSONException {
        awaitReady(); return new JSONObject(context.toString());
    }
    OperationResources.Slot retain(Closeable resource) throws IOException {
        var slot = resources.reserve(); slot.attach(resource);
        if (closed.get()) throw new IOException("Linux session ended");
        return slot;
    }

    JSONObject request(int op, int bus, JSONObject args, int timeout) throws IOException, JSONException {
        if (timeout < 1 || timeout > 10000) throw new IllegalArgumentException("timeoutMillis must be 1..10000");
        long deadline = android.os.SystemClock.uptimeMillis() + timeout;
        try {
            // EVENT_WAIT: bounded admission to the single guest command channel; expiry does not submit work.
            if (!command.tryLock(timeout, TimeUnit.MILLISECONDS)) throw new IOException("Linux automation busy");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", error); }
        try {
            await(hello, remaining(deadline));
            if (closed.get()) throw new IOException("Linux session ended");
            timeout = remaining(deadline);
            int requestId = ++serial;
            if (requestId == 0) requestId = ++serial;
            byte[] frame = LinuxBusWire.request(requestId, op, bus, timeout, args);
            CompletableFuture<LinuxBusWire.Message> completion = new CompletableFuture<>();
            pending.put(requestId, completion);
            try {
                output.writeInt(frame.length); output.write(frame); output.flush();
                var message = await(completion, timeout + 5000L);
                if (message.kind() == 1) throw new BusError(message.data().optString("errorName"), message.data().optString("errorDetail"));
                return message.data();
            } catch (BusError error) { throw error; }
            catch (IOException error) { close(); throw error; }
            finally { pending.remove(requestId); }
        } finally { command.unlock(); }
    }

    private static int remaining(long deadline) throws IOException {
        long remaining = deadline - android.os.SystemClock.uptimeMillis();
        if (remaining <= 0) throw new IOException("Linux automation command admission deadline");
        return (int)remaining;
    }

    JSONArray call(int bus, String destination, String path, String iface, String member, JSONArray args, int timeout)
            throws IOException, JSONException {
        return request(1, bus, new JSONObject().put("destination", destination).put("path", path)
                .put("interface", iface).put("member", member).put("arguments", args), timeout).getJSONArray("values");
    }

    synchronized long generation() { return generation; }
    synchronized CompletableFuture<Long> after(long observed) {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("Linux session ended"));
        return generation != observed ? CompletableFuture.completedFuture(generation) : changed;
    }
    synchronized JSONObject events(long after) throws JSONException {
        JSONArray list = new JSONArray();
        for (JSONObject event : events) if (event.getLong("sequence") > after) list.put(event);
        return new JSONObject().put("events", list).put("sequence", generation)
                .put("overflow", after < discarded).put("sessionId", id);
    }
    private void read() {
        try {
            while (!closed.get()) {
                var message = LinuxBusWire.read(input, () -> channel.readTimeout(5000));
                channel.readTimeout(0);
                if (message.kind() == 3 && message.id() == 0 && !hello.isDone()) {
                    JSONArray values = message.data().getJSONArray("values");
                    context = new JSONObject().put("guestUid", values.getJSONObject(0).getLong("value"))
                            .put("agentPid", values.getJSONObject(1).getLong("value"));
                    String[] names = {"directory", "shell", "pipeHelper", "display", "waylandDisplay"};
                    for (int i = 0; i < names.length; ++i) context.put(names[i], values.getJSONObject(i + 2).getString("value"));
                    hello.complete(null);
                }
                else if (message.kind() == 2 && message.id() == 0) {
                    CompletableFuture<Long> notify;
                    long sequence;
                    synchronized (this) {
                        sequence = ++generation;
                        events.addLast(message.data().put("bus", busName(message.bus())).put("sequence", sequence));
                        eventSizes.addLast(message.bytes()); eventBytes += message.bytes();
                        while (events.size() > 256 || eventBytes > LinuxBusWire.LIMIT) {
                            discarded = events.removeFirst().getLong("sequence"); eventBytes -= eventSizes.removeFirst();
                        }
                        notify = changed; changed = new CompletableFuture<>();
                    }
                    notify.complete(sequence);
                } else {
                    var completion = pending.get(message.id());
                    if (completion == null || !completion.complete(message)) throw new IOException("Unexpected Linux automation response");
                }
            }
        } catch (IOException | JSONException | RuntimeException error) { hello.completeExceptionally(error); }
        finally { close(); }
    }

    static String busName(int bus) { return switch (bus) { case 0 -> "session"; case 1 -> "system"; default -> "accessibility"; }; }
    static int bus(String name) {
        return switch (name) { case "session" -> 0; case "system" -> 1; case "accessibility" -> 2; default -> throw new IllegalArgumentException("Unknown bus"); };
    }
    static <T> T await(CompletableFuture<T> result, long timeout) throws IOException {
        try {
            // EVENT_WAIT: exact reply, event or peer death; timeout is failure, never evidence of completion.
            return result.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Interrupted", error); }
        catch (ExecutionException | TimeoutException error) { throw new IOException("Linux automation wait failed", error); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        IOException error = new IOException("Linux session ended");
        hello.completeExceptionally(error);
        pending.values().forEach(future -> future.completeExceptionally(error));
        synchronized (this) { changed.completeExceptionally(error); events.clear(); eventSizes.clear(); eventBytes = 0; }
        channel.close();
        resources.close();
        disconnected.run();
    }
    static final class BusError extends IOException {
        final String name;
        BusError(String name, String detail) { super(name + ": " + detail); this.name = name; }
    }
}
