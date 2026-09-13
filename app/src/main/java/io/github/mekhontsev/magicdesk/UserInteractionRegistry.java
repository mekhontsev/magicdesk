package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/** Process-local results survive caller disconnects, not an application restart. */
final class UserInteractionRegistry implements AutoCloseable {
    private static final int MAX_PENDING = 16;
    private static final int MAX_RETAINED = 64;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    private static final class Entry {
        final String id = UUID.randomUUID().toString();
        final UserInteractionRequest request;
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        String state = "pending";
        String error = "";
        boolean presented;
        JSONObject result = new JSONObject();
        Entry(UserInteractionRequest request) { this.request = request; }
    }

    synchronized String begin(UserInteractionRequest request) {
        if (closed) throw new IllegalStateException("user interaction runtime is closed");
        if (entries.values().stream().filter(e -> e.state.equals("pending")).count() >= MAX_PENDING) {
            throw new IllegalStateException("too many pending user interactions; close an existing request");
        }
        final var iterator = entries.values().iterator();
        while (entries.size() >= MAX_RETAINED && iterator.hasNext()) {
            if (!iterator.next().state.equals("pending")) iterator.remove();
        }
        final Entry entry = new Entry(request);
        entries.put(entry.id, entry);
        return entry.id;
    }

    synchronized UserInteractionRequest pending(String id) {
        final Entry entry = entries.get(id);
        return entry != null && entry.state.equals("pending") ? entry.request : null;
    }

    synchronized void presented(String id) {
        final Entry entry = entries.get(id);
        if (entry != null && entry.state.equals("pending")) entry.presented = true;
    }

    void complete(String id, String state, JSONObject result, String error) {
        if (!List.of("completed", "cancelled", "expired", "failed").contains(state)) {
            throw new IllegalArgumentException("invalid completion state");
        }
        final Entry entry;
        synchronized (this) {
            entry = entries.get(id);
            if (entry == null || !entry.state.equals("pending")) return;
            try { entry.result = result == null ? new JSONObject() : new JSONObject(result.toString()); }
            catch (JSONException invalid) { throw new IllegalArgumentException(invalid); }
            entry.state = state;
            entry.error = error == null ? "" : error;
            notifyAll();
        }
        // Closing a window can call back into the registry. Never notify UI under its lock.
        entry.finished.complete(null);
    }

    CompletableFuture<Void> whenFinished(String id, Runnable callback) {
        final CompletableFuture<Void> finished;
        synchronized (this) {
            final Entry entry = entries.get(id);
            finished = entry == null ? CompletableFuture.completedFuture(null) : entry.finished;
        }
        return finished.thenRun(callback);
    }

    synchronized JSONObject result(String id, long waitMillis) throws JSONException {
        if (waitMillis < 0 || waitMillis > 30_000) {
            throw new IllegalArgumentException("waitMillis must be between 0 and 30000");
        }
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        Entry entry = entries.get(id);
        while (entry != null && entry.state.equals("pending") && waitMillis > 0) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                EventDrivenWaits.await(this, EventDrivenWaits.Reason.USER_INTERACTION,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            entry = entries.get(id);
        }
        final JSONObject result = new JSONObject().put("requestId", id)
                .put("state", entry == null ? "not_found" : entry.state);
        if (entry != null) result.put("kind", entry.request.kind()).put("presented", entry.presented)
                .put("result", new JSONObject(entry.result.toString())).put("failure", entry.error);
        return result;
    }

    @Override public void close() {
        final List<String> ids;
        synchronized (this) { closed = true; ids = List.copyOf(entries.keySet()); }
        for (String id : ids) complete(id, "cancelled", null, "runtime closed");
    }
}
