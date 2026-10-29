package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Existing pipe-shell relay, launched inside one captured Linux session. No second job protocol. */
final class LinuxCommandStream implements CommandStream {
    private final OperationResources resources = new OperationResources();
    private final AtomicBoolean closed = new AtomicBoolean(), offered = new AtomicBoolean();
    private final CompletableFuture<ExecutorSocketChannel> ready = new CompletableFuture<>();
    private final LinuxAutomationLaunch.Target target;
    private OperationResources.Slot owner;
    private ExecutorSocketChannel channel;

    LinuxCommandStream(LinuxAutomationLaunch.Target target) throws IOException {
        this.target = target;
        byte[] nonce = new byte[32]; new java.security.SecureRandom().nextBytes(nonce);
        String token = java.util.HexFormat.of().formatHex(nonce);
        try {
            owner = target.bus().retain(this);
            var endpointSlot = resources.reserve();
            ExecutorSocketEndpoint endpoint = target.owner().endpoint(target, fd -> {
                if (!offered.compareAndSet(false, true)) return;
                var socket = ParcelFileDescriptor.dup(fd.getFileDescriptor());
                ExecutorSocketChannel accepted;
                try { accepted = new ExecutorSocketChannel(socket); }
                catch (IOException error) { socket.close(); throw error; }
                var channelSlot = resources.reserve(); channelSlot.attach(accepted);
                accepted.readTimeout(5000);
                new Thread(() -> {
                    try {
                        var input = new DataInputStream(accepted.input);
                        if (input.readUnsignedByte() != 17 || input.readInt() != 64) throw new IOException("Invalid Linux console admission");
                        byte[] supplied = new byte[64]; input.readFully(supplied);
                        if (!java.security.MessageDigest.isEqual(supplied, token.getBytes(StandardCharsets.US_ASCII)))
                            throw new IOException("Wrong Linux console admission");
                        accepted.readTimeout(0);
                        if (!ready.complete(accepted)) accepted.close();
                    } catch (IOException error) { ready.completeExceptionally(error); channelSlot.close(); }
                }, "Linux console admission").start();
            }, error -> { ready.completeExceptionally(error); close(); });
            endpointSlot.attach(endpoint);
            JSONObject context = target.bus().context();
            String helper = context.getString("pipeHelper"), shell = context.getString("shell");
            if (helper.isEmpty()) throw new IOException("Linux console helper unavailable");
            String address = endpoint.name;
            if (target.uid() == 0 && context.getLong("guestUid") != 0)
                throw new IOException("Delegated root guest console endpoint is not mapped");
            target.bus().request(6, 0, new JSONObject().put("argv", new JSONArray().put(helper).put("--pipe-unix")
                    .put(address).put(token).put(shell)).put("environment", new JSONArray()), 5000);
            channel = LinuxBusConnection.await(ready, 5000);
            endpointSlot.close();
            if (closed.get()) throw new IOException("Linux console closed during startup");
        } catch (IOException | JSONException | RuntimeException error) {
            close(); throw new IOException("Cannot open Linux console: " + ShellAccess.usefulMessage(error), error);
        }
    }
    @Override public InputStream inputStream() throws IOException { requireCurrent(); return channel.input; }
    @Override public void writeLine(String line) throws IOException {
        requireCurrent(); channel.output.write((line + "\n").getBytes(StandardCharsets.UTF_8)); channel.output.flush();
    }
    private void requireCurrent() throws IOException {
        if (closed.get() || !target.bus().alive()) { close(); throw new IOException("Linux session ended"); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ready.completeExceptionally(new IOException("Linux console closed"));
        resources.close();
        if (owner != null) owner.close();
    }
}
