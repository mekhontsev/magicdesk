package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Launch admission only. Each connected guest gets its own identity, bus and lifetime. */
final class LinuxAutomationLaunch implements Closeable {
    record Target(LinuxBusConnection bus, String graphicsId, String environment, int uid, LinuxAutomationLaunch owner) { }
    private static final Map<String, Target> TARGETS = new ConcurrentHashMap<>();
    private final Set<LinuxBusConnection> connections = ConcurrentHashMap.newKeySet();
    private final Set<ParcelFileDescriptor> admitting = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ThreadPoolExecutor admissions = new java.util.concurrent.ThreadPoolExecutor(
            0, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),
            task -> new Thread(task, "Linux automation admission"));
    private final OperationResources resources = new OperationResources();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String token, helper;
    private ExecutorSocketEndpoint endpoint;
    private ExecutorSocketEndpoint.Factory factory;

    LinuxAutomationLaunch(Context context, CommandExecution commands, String graphicsId, String environment) throws IOException {
        byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random);
        token = java.util.HexFormat.of().formatHex(random);
        helper = context.getApplicationInfo().nativeLibraryDir + "/libmagicdesk_linux_settings.so";
        try {
            var brokerSlot = resources.reserve();
            var broker = new ExecutorEndpointBroker(context, commands, ignored -> close()); brokerSlot.attach(broker);
            factory = broker.factory;
            var endpointSlot = resources.reserve();
            endpoint = new ExecutorSocketEndpoint(commands.uid, 5000, broker.factory, fd -> {
                ParcelFileDescriptor owned = ParcelFileDescriptor.dup(fd.getFileDescriptor());
                // Admission never blocks the Binder thread on an untrusted peer.
                admitting.add(owned);
                try { admissions.execute(() -> admit(owned, commands.uid, graphicsId, environment)); }
                catch (java.util.concurrent.RejectedExecutionException error) { admitting.remove(owned); owned.close(); }
            }, ignored -> close());
            endpointSlot.attach(endpoint);
            if (closed.get()) throw new IOException("Linux automation admission closed");
        } catch (IOException | RuntimeException error) { close(); throw error; }
    }

    private void admit(ParcelFileDescriptor socket, int uid, String graphicsId, String environment) {
        LinuxBusConnection connection = null;
        try {
            synchronized (connections) {
                if (closed.get() || connections.size() >= 16) throw new IOException("Linux automation session limit");
                final LinuxBusConnection[] owner = new LinuxBusConnection[1];
                connection = new LinuxBusConnection(socket, token.getBytes(StandardCharsets.US_ASCII), () -> {
                    LinuxBusConnection ended = owner[0];
                    if (ended != null) { TARGETS.remove(ended.id); connections.remove(ended); }
                });
                owner[0] = connection;
                connections.add(connection);
            }
            connection.awaitReady();
            TARGETS.put(connection.id, new Target(connection, graphicsId, environment, uid, this));
            if (closed.get() || !connection.alive()) {
                TARGETS.remove(connection.id); connections.remove(connection); connection.close();
            }
        } catch (IOException error) {
            android.util.Log.w("MagicDeskLinuxAutomation", "Guest admission failed", error);
            CompatibilityDiagnostics.record("LINUX-AUTOMATION-002", "Linux automation connection failed",
                    ShellAccess.usefulMessage(error), error);
            if (connection != null) { connections.remove(connection); connection.close(); }
            else try { socket.close(); } catch (IOException ignored) { }
        } finally { admitting.remove(socket); }
    }

    String exports() {
        if (closed.get()) return "";
        String privateBus = "";
        for (LinuxBusConnection connection : connections) {
            privateBus = connection.privateBusAddress();
            if (!privateBus.isEmpty()) break;
        }
        return TerminalLaunchEnvironment.exports(Map.of("MAGICDESK_AUTOMATION_SOCKET", endpoint.name,
                "MAGICDESK_AUTOMATION_TOKEN", token, "MAGICDESK_AUTOMATION_HELPER", helper,
                "MAGICDESK_SESSION_BUS_ADDRESS", privateBus));
    }

    static Target target(String id) throws IOException {
        Target target = TARGETS.get(id);
        if (target == null || !target.bus.alive()) throw new IOException("Unknown or ended Linux session: " + id);
        return target;
    }
    static JSONArray targets() throws JSONException {
        JSONArray list = new JSONArray();
        for (Target target : TARGETS.values()) if (target.bus.alive()) list.put(new JSONObject()
                .put("sessionId", target.bus.id).put("backend", "atspi").put("graphicsSessionId", target.graphicsId)
                .put("environment", target.environment).put("executorUid", target.uid));
        return list;
    }
    ExecutorSocketEndpoint endpoint(Target target, ExecutorSocketEndpoint.Sink sink, java.util.function.Consumer<Throwable> failed) throws IOException {
        if (closed.get() || !target.bus.alive()) throw new IOException("Linux session ended");
        return new ExecutorSocketEndpoint(target.uid, 5000, factory, sink, failed);
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        resources.close();
        admissions.shutdownNow();
        for (ParcelFileDescriptor socket : admitting) try { socket.close(); } catch (IOException ignored) { }
        admitting.clear();
        for (LinuxBusConnection connection : connections) connection.close();
        connections.clear();
    }
}
