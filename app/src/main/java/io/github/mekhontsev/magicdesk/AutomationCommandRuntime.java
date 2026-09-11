package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import java.io.IOException;
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
    private IBinder mConfiguredShell;
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
            return CommandShellEnvironment.termuxSetup(endpoint(), mContext.getApplicationInfo().sourceDir);
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
        try (client) {
            if (!client.getInetAddress().isLoopbackAddress()) return;
            // Bound only the local protocol handshake, not execution of a long-running command.
            client.setSoTimeout(5_000);
            final JSONObject request = AutomationCommandWire.read(client.getInputStream(), AutomationCommandWire.REQUEST_LIMIT);
            final boolean keyMatches = MessageDigest.isEqual(mKey.getBytes(StandardCharsets.UTF_8),
                    request.optString("key").getBytes(StandardCharsets.UTF_8));
            if (!keyMatches || mClosed) return;
            client.setSoTimeout(0);
            final DesktopAutomationResult result;
            if (!BuildConfig.SOURCE_ID.equals(request.optString("build"))) {
                result = DesktopAutomationResult.failure(DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                        "CLI build differs from the running MagicDesk process; reopen MagicDesk", false);
            } else {
                result = commands.execute(request.getString("name"), request.getJSONObject("arguments"));
            }
            final JSONObject encoded = result.toJson();
            if (result.image != null) encoded.put("image", new JSONObject()
                    .put("mimeType", result.image.mimeType).put("data", result.image.base64Data));
            AutomationCommandWire.write(client.getOutputStream(), encoded, AutomationCommandWire.RESPONSE_LIMIT);
        } catch (IOException | JSONException | RuntimeException error) {
            if (!mClosed) Log.w("MagicDeskCli", "Command channel request failed", error);
        } finally { mClients.remove(client); }
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
        commands.close();
    }
}
