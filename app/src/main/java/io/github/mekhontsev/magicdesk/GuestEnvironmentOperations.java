package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Shared background ownership for explicit guest CLI operations, independent of MCP connections. */
final class GuestEnvironmentOperations {
    private static GuestEnvironmentOperations current;
    private final LinkedHashMap<String, Operation> operations = new LinkedHashMap<>();
    private boolean closed;
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(4,
            action -> new Thread(action, "GuestEnvironmentOperation"));

    static synchronized GuestEnvironmentOperations get() {
        if (current == null) current = new GuestEnvironmentOperations();
        return current;
    }

    static synchronized void closeCurrent() {
        if (current == null) return;
        current.close();
        current = null;
    }

    private void close() {
        final List<Operation> pending;
        synchronized (this) { closed = true; pending = List.copyOf(operations.values()); workers.shutdown(); }
        for (Operation operation : pending) operation.cancel();
    }

    static String read(String library, String... arguments) throws IOException {
        requireShell();
        var session = new ShellCommandSession("/data/local/tmp");
        try {
            var output = new java.io.ByteArrayOutputStream();
            var result = session.execute(inLibrary(GuestEnvironmentCatalog.command(arguments), library), (bytes, offset, count) -> {
                if (count > GuestImageFiles.JSON_LIMIT - output.size()) throw new IOException("Guest result exceeds size limit");
                output.write(bytes, offset, count);
            });
            if (result.exitCode() != 0) throw new IOException(result.stderr());
            return output.toString(StandardCharsets.UTF_8).trim();
        } finally { session.close(); }
    }

    synchronized JSONObject start(JSONArray values, String library) throws Exception {
        requireShell();
        if (closed) throw new IOException("Guest operations are closed");
        if (values.length() == 0 || values.length() > 256) throw new IllegalArgumentException("Invalid guest arguments");
        String[] arguments = new String[values.length()];
        int size = 0;
        for (int i = 0; i < arguments.length; ++i) {
            arguments[i] = values.getString(i);
            if (arguments[i].indexOf('\0') >= 0 || (size += arguments[i].length()) > 65536)
                throw new IllegalArgumentException("Invalid guest arguments");
        }
        if (!List.of("install", "restore", "backup", "remove", "prune", "dns", "exec", "run").contains(arguments[0]))
            throw new IllegalArgumentException("Unsupported background guest operation");
        if (operations.values().stream().filter(value -> !value.done).count() >= 4)
            throw new IOException("Too many active guest operations");
        while (operations.size() >= 64) {
            String oldest = operations.entrySet().stream().filter(entry -> entry.getValue().done).findFirst().orElseThrow().getKey();
            operations.remove(oldest);
        }
        String command = inLibrary(GuestEnvironmentCatalog.command(arguments), library);
        Operation operation = new Operation(command, ShellAccess.currentSnapshot().uid);
        operations.put(operation.id, operation);
        workers.execute(operation::run);
        return operation.snapshot();
    }

    synchronized Operation require(String id) throws IOException {
        Operation result = operations.get(id);
        if (result == null) throw new IOException("Guest operation not found or no longer retained");
        return result;
    }

    private static void requireShell() throws IOException {
        if (!ShellAccess.isReady()) throw new IOException("Privileged command service is unavailable");
        GuestLaunchPlan.requireIdentity(ShellAccess.currentSnapshot().uid);
    }

    private static String inLibrary(String command, String library) {
        return library.isEmpty() ? command : "MAGICDESK_GUEST_HOME="
                + ShellCommandLine.quote(GuestEnvironment.absolute(library, "environment library")) + " " + command;
    }

    static final class Operation {
        final String id = "guest-" + UUID.randomUUID();
        final String command;
        final int uid;
        final ShellCommandSession session = new ShellCommandSession("/data/local/tmp");
        final byte[] tail = new byte[65536];
        int used;
        long bytes, revision;
        volatile boolean done;
        boolean cancellation;
        Integer exitCode;
        String error = "";

        Operation(String command, int uid) { this.command = command; this.uid = uid; }

        void run() {
            try {
                requireShell();
                if (ShellAccess.currentSnapshot().uid != uid) throw new IOException("Selected executor identity changed");
                var result = session.execute("/system/bin/sh -c " + ShellCommandLine.quote(command) + " 2>&1", this::append);
                synchronized (this) { exitCode = result.exitCode(); }
            } catch (Exception failure) {
                synchronized (this) { error = ShellAccess.usefulMessage(failure); }
            } finally {
                session.close();
                synchronized (this) { done = true; revision++; notifyAll(); }
            }
        }

        synchronized void append(byte[] buffer, int offset, int length) {
            bytes += length;
            if (length >= tail.length) { offset += length - tail.length; length = tail.length; used = 0; }
            int discard = Math.max(0, used + length - tail.length);
            if (discard > 0) { System.arraycopy(tail, discard, tail, 0, used - discard); used -= discard; }
            System.arraycopy(buffer, offset, tail, used, length); used += length;
            revision++; notifyAll();
        }

        void cancel() {
            synchronized (this) {
                if (done) return;
                cancellation = true; revision++; notifyAll();
            }
            session.close();
        }

        synchronized JSONObject observe(long after, int timeout) throws Exception {
            if (timeout < 0 || timeout > 30000 || after < -1) throw new IllegalArgumentException("Invalid observation bound");
            long end = System.nanoTime() + timeout * 1_000_000L;
            while (!done && revision <= after && timeout > 0) {
                long remaining = (end - System.nanoTime()) / 1_000_000;
                if (remaining <= 0) break;
                // EVENT_WAIT: command output, cancellation or exit; expiry returns the current state, never cancels work.
                EventDrivenWaits.await(this, EventDrivenWaits.Reason.GUEST_OPERATION, remaining);
            }
            return snapshot();
        }

        synchronized JSONObject snapshot() throws org.json.JSONException {
            String state = !done ? "running" : exitCode != null && exitCode == 0 ? "completed"
                    : cancellation ? "cancelled" : "failed";
            return new JSONObject().put("operationId", id).put("state", state).put("revision", revision)
                    .put("executorUid", uid).put("cancelRequested", cancellation)
                    .put("exitCode", exitCode == null ? JSONObject.NULL : exitCode)
                    .put("output", new String(tail, 0, used, StandardCharsets.UTF_8)).put("outputBytes", bytes)
                    .put("truncated", bytes > used).put("error", error).put("safeToRetry", false);
        }
    }
}
