package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

/** App-owned commands; MCP and inherited shell channels are adapters, not service owners. */
final class AutomationCommandRuntime implements AutoCloseable {
    private static AutomationCommandRuntime sCurrent;
    final AutomationCommands commands;
    private final Context mContext;
    private final Set<Socket> mClients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor mWorkers = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                final Thread thread = new Thread(runnable, "MagicDeskCliCommand");
                thread.setDaemon(true);
                return thread;
            });
    private ServerSocket mServer;
    private String mKey;
    private volatile IBinder mConfiguredShell;
    private final AutomationCommandLeases mLeases = new AutomationCommandLeases();
    private final java.util.Map<String, DesktopAutomationConsoleSessions> mLeaseConsoles = new ConcurrentHashMap<>();
    private volatile boolean mClosed;

    private AutomationCommandRuntime(Context context) {
        mContext = context.getApplicationContext();
        commands = new AutomationCommands(mContext);
    }

    static synchronized AutomationCommandRuntime get(Context context) {
        if (sCurrent == null) sCurrent = new AutomationCommandRuntime(context);
        return sCurrent;
    }

    static void closeCurrent() {
        final AutomationCommandRuntime runtime;
        synchronized (AutomationCommandRuntime.class) { runtime = sCurrent; sCurrent = null; }
        if (runtime != null) runtime.close();
    }

    synchronized void prepareShell(IShellCommandService service) throws IOException, RemoteException {
        if (mConfiguredShell == service.asBinder()) return;
        service.configureCommandEnvironment(endpoint(), mContext.getApplicationInfo().sourceDir);
        mConfiguredShell = service.asBinder();
    }

    String prepareTermux(TermuxIntegration.Endpoint termux) {
        termux.requireAvailable();
        try {
            return CommandShellEnvironment.termuxSetup(endpoint(), mContext.getApplicationInfo().sourceDir,
                    mContext.getApplicationInfo().nativeLibraryDir);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot prepare the local command channel", error);
        }
    }

    private synchronized String endpoint() throws IOException {
        if (mClosed) throw new IOException("MagicDesk command runtime is closed");
        if (mServer == null) {
            final ServerSocket server = new ServerSocket();
            try { server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8); }
            catch (IOException error) { server.close(); throw error; }
            final byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            mKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
            mServer = server;
            final Thread acceptor = new Thread(() -> accept(server), "MagicDeskCliAccept");
            acceptor.setDaemon(true);
            acceptor.start();
        }
        return mServer.getLocalPort() + ":" + mKey;
    }

    private void accept(ServerSocket server) {
        while (!mClosed) {
            try {
                final Socket client = server.accept();
                synchronized (this) {
                    if (mClosed) { client.close(); return; }
                    mClients.add(client);
                }
                try { mWorkers.execute(() -> handle(client)); }
                catch (java.util.concurrent.RejectedExecutionException error) {
                    mClients.remove(client);
                    client.close();
                }
            } catch (IOException error) {
                if (!mClosed) Log.w("MagicDeskCli", "Command channel stopped", error);
                return;
            }
        }
    }

    private void handle(Socket client) {
        boolean retained = false;
        try {
            if (!client.getInetAddress().isLoopbackAddress()) return;
            // Bound only the local protocol handshake, not execution of a long-running command.
            client.setSoTimeout(5_000);
            final DataInputStream input = new DataInputStream(client.getInputStream());
            final int framing = input.readInt();
            if (framing == AutomationCliWire.MAGIC) {
                retained = handleCli(client, input);
                return;
            }
            final JSONObject request = AutomationCommandWire.read(input, framing, AutomationCommandWire.REQUEST_LIMIT);
            final boolean keyMatches = MessageDigest.isEqual(mKey.getBytes(StandardCharsets.UTF_8),
                    request.optString("key").getBytes(StandardCharsets.UTF_8));
            if (mClosed) return;
            final AutomationCommandLeases.Lease lease = keyMatches ? null : mLeases.acquire(request.optString("key"), client);
            client.setSoTimeout(0);
            final DesktopAutomationResult result;
            try {
                if (!BuildConfig.SOURCE_ID.equals(request.optString("build"))) {
                    result = DesktopAutomationResult.failure(DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                            "CLI build differs from the running MagicDesk process; reopen MagicDesk", false);
                } else if (lease == null) {
                    result = commands.execute(request.getString("name"), request.getJSONObject("arguments"));
                } else {
                    lease.requireActive();
                    var consoles = mLeaseConsoles.get(lease.key);
                    if (consoles == null) throw new IOException("Command owner closed");
                    result = commands.execute(request.getString("name"), request.getJSONObject("arguments"), consoles);
                }
                AutomationCommandWire.write(client.getOutputStream(), encode(result), AutomationCommandWire.RESPONSE_LIMIT);
            } finally { if (lease != null) lease.release(client); }
        } catch (IOException | JSONException | RuntimeException error) {
            if (!mClosed) Log.w("MagicDeskCli", "Command channel request failed", error);
        } finally {
            if (!retained) {
                mClients.remove(client);
                try { client.close(); } catch (IOException ignored) { }
            }
        }
    }

    private boolean handleCli(Socket client, DataInputStream input) throws IOException {
        int operation = input.readInt();
        String key = AutomationCliWire.read(input, 128);
        String build = AutomationCliWire.read(input, 128);
        if (!BuildConfig.SOURCE_ID.equals(build)) throw new IOException("CLI build differs from MagicDesk; reopen the launch");
        var output = new DataOutputStream(client.getOutputStream());
        if (operation == AutomationCliWire.LEASE) {
            if (!MessageDigest.isEqual(mKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)))
                throw new IOException("Invalid command owner");
            final IBinder shell = mConfiguredShell;
            var consoles = new DesktopAutomationConsoleSessions(false);
            var lease = mLeases.create(client, () -> !mClosed && mConfiguredShell == shell
                    && (shell == null || shell.isBinderAlive()), consoles::closeAll);
            mLeaseConsoles.put(lease.key, consoles);
            try {
                output.writeInt(0);
                AutomationCliWire.write(output, lease.key);
                client.setSoTimeout(0);
                Thread owner = new Thread(() -> {
                    try {
                        // EVENT_WAIT: registration EOF revokes the lease; no heartbeat or expiry polling.
                        input.read();
                    } catch (IOException ignored) {
                    } finally {
                        lease.close(); mLeaseConsoles.remove(lease.key); mClients.remove(client);
                    }
                }, "MagicDeskCliOwner");
                owner.setDaemon(true); owner.start();
                return true;
            } catch (IOException | RuntimeException error) {
                lease.close(); mLeaseConsoles.remove(lease.key); throw error;
            }
        }
        if (operation != AutomationCliWire.INVOKE) throw new IOException("Invalid CLI operation");
        var lease = mLeases.acquire(key, client);
        try {
            final var consoles = mLeaseConsoles.get(lease.key);
            if (consoles == null) throw new IOException("Command owner closed");
            client.setSoTimeout(120_000);
            AutomationCliWire.serve(input, output, (name, args) -> {
                lease.requireActive();
                return encode(commands.execute(name, args, consoles));
            });
        } finally { lease.release(client); }
        return false;
    }

    private static JSONObject encode(DesktopAutomationResult result) throws JSONException {
        var encoded = result.toJson();
        if (result.image != null) encoded.put("image", new JSONObject()
                .put("mimeType", result.image.mimeType).put("data", result.image.base64Data));
        return encoded;
    }

    @Override public void close() {
        synchronized (this) {
            if (mClosed) return;
            mClosed = true;
            if (mServer != null) {
                try { mServer.close(); } catch (IOException error) { Log.w("MagicDeskCli", "Close listener", error); }
            }
            for (Socket socket : mClients) {
                try { socket.close(); } catch (IOException ignored) { }
            }
            mClients.clear();
        }
        mWorkers.shutdownNow();
        mLeases.close();
        commands.close();
    }
}
