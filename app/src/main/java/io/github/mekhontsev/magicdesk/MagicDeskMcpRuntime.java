package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.IOException;

/** One automation backend with independently owned local and opt-in network listeners. */
final class MagicDeskMcpRuntime implements Closeable {
    private static volatile MagicDeskMcpRuntime sActive;
    private static volatile Snapshot sLast = Snapshot.inactive();
    private final Context mContext;
    private MagicDeskMcpPreferences.Values mSettings;
    private McpJsonRpcHandler mHandler;
    private McpJsonRpcHandler mNetworkHandler;
    private MagicDeskMcpBackend mBackend;
    private MagicDeskMcpHttpServer mLocal;
    private MagicDeskMcpHttpServer mNetwork;
    private String mNetworkEndpoint = "";
    private String mNetworkError = "";
    private String mLocalError = "";
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private boolean mClosed;

    MagicDeskMcpRuntime(final Context context) {
        mContext = context.getApplicationContext();
    }

    synchronized void reconcile() {
        if (mClosed) return;
        mSettings = MagicDeskMcpPreferences.load(mContext);
        sActive = this;
        if (!mSettings.enabled || mSettings.token.isEmpty()) {
            stop();
            return;
        }
        if (mBackend == null) {
            mBackend = new MagicDeskMcpBackend(mContext);
            mHandler = new McpJsonRpcHandler(mBackend.scoped(false));
            mNetworkHandler = new McpJsonRpcHandler(mBackend.scoped(true));
        }
        if (mLocal == null) {
            final MagicDeskMcpHttpServer server = new MagicDeskMcpHttpServer(mHandler,
                    () -> {
                        final var values = MagicDeskMcpPreferences.load(mContext);
                        return values.enabled ? values.token : "";
                    });
            try {
                server.start(MagicDeskMcpPreferences.HOST, MagicDeskMcpPreferences.PORT);
                mLocal = server;
                mLocalError = "";
            } catch (IOException | RuntimeException error) {
                server.close();
                mLocalError = ShellAccess.usefulMessage(error);
            }
        }
        if (!mSettings.networkEnabled) {
            stopNetwork();
            unobserveNetwork();
            mNetworkError = "";
            return;
        }
        try {
            observeNetwork();
            final McpNetworkInterfaces.Binding binding =
                    McpNetworkInterfaces.find(mSettings.networkInterface);
            if (binding == null) throw new IOException("Selected network interface is unavailable");
            final String endpoint = binding.endpoint(mSettings.networkPort);
            if (mNetwork != null && endpoint.equals(mNetworkEndpoint)) return;
            stopNetwork();
            final MagicDeskMcpHttpServer server = new MagicDeskMcpHttpServer(mNetworkHandler,
                    () -> {
                        final var values = MagicDeskMcpPreferences.load(mContext);
                        return values.enabled && values.networkEnabled ? values.networkToken : "";
                    });
            try {
                server.startNetwork(binding.address, mSettings.networkPort);
                mNetwork = server;
                mNetworkEndpoint = endpoint;
                mNetworkError = "";
            } catch (IOException | RuntimeException error) {
                server.close();
                throw error;
            }
        } catch (IOException | RuntimeException error) {
            stopNetwork();
            mNetworkError = ShellAccess.usefulMessage(error);
        }
    }

    private void observeNetwork() {
        if (mNetworkCallback != null) return;
        final ConnectivityManager manager = mContext.getSystemService(ConnectivityManager.class);
        if (manager == null) throw new IllegalStateException("Network observation is unavailable");
        final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                reconcile();
            }
            @Override public void onLost(Network network) {
                reconcile();
            }
        };
        manager.registerNetworkCallback(new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
        mNetworkCallback = callback;
    }

    private void unobserveNetwork() {
        if (mNetworkCallback == null) return;
        try {
            mContext.getSystemService(ConnectivityManager.class)
                    .unregisterNetworkCallback(mNetworkCallback);
        } catch (RuntimeException error) {
            mNetworkError = ShellAccess.usefulMessage(error);
        } finally {
            mNetworkCallback = null;
        }
    }

    private void stopNetwork() {
        if (mNetwork != null) mNetwork.close();
        mNetwork = null;
        mNetworkEndpoint = "";
    }

    private void stop() {
        unobserveNetwork();
        stopNetwork();
        if (mLocal != null) mLocal.close();
        mLocal = null;
        // Closing/rebinding the network socket must not close local shell sessions.
        if (mBackend != null) mBackend.close();
        mBackend = null;
        mHandler = null;
        mNetworkHandler = null;
    }

    @Override public synchronized void close() {
        if (mClosed) return;
        mClosed = true;
        stop();
        sLast = currentSnapshot();
        if (sActive == this) sActive = null;
    }

    static Snapshot snapshot() {
        final MagicDeskMcpRuntime runtime = sActive;
        return runtime == null ? sLast : runtime.currentSnapshot();
    }

    private synchronized Snapshot currentSnapshot() {
        if (mSettings == null) return Snapshot.inactive();
        final MagicDeskMcpHttpServer.Snapshot local = mLocal == null ? null : mLocal.snapshot();
        final MagicDeskMcpHttpServer.Snapshot network = mNetwork == null ? null : mNetwork.snapshot();
        return new Snapshot(mSettings.enabled, local != null && local.running,
                mSettings.localAccess, mSettings.networkAccess, mSettings.endpoint(),
                local == null ? 0 : local.connections, local == null ? 0 : local.requests,
                local == null ? 0 : local.rejected,
                local == null || local.lastError.isEmpty() ? mLocalError : local.lastError,
                mSettings.networkEnabled, mSettings.networkInterface, mSettings.networkPort,
                mNetworkEndpoint, network != null && network.running,
                network == null || network.lastError.isEmpty() ? mNetworkError : network.lastError,
                network == null ? 0 : network.requests, network == null ? 0 : network.rejected);
    }

    static JSONObject snapshotJson() throws JSONException {
        final Snapshot snapshot = snapshot();
        return new JSONObject().put("enabled", snapshot.enabled).put("running", snapshot.running)
                .put("localPermissions", snapshot.localAccess.toJson())
                .put("networkPermissions", snapshot.networkAccess.toJson())
                .put("endpoint", snapshot.endpoint).put("connections", snapshot.connections)
                .put("requests", snapshot.requests).put("rejected", snapshot.rejected)
                .put("lastError", snapshot.lastError).put("network", snapshot.networkJson());
    }

    static final class Snapshot {
        final boolean enabled, running;
        final McpAccessPolicy localAccess, networkAccess;
        final String endpoint, lastError;
        final long connections, requests, rejected;
        final boolean networkEnabled, networkRunning;
        final String networkInterface, networkEndpoint, networkError;
        final int networkPort;
        final long networkRequests, networkRejected;

        Snapshot(boolean enabled, boolean running, McpAccessPolicy localAccess, McpAccessPolicy networkAccess,
                String endpoint, long connections, long requests, long rejected, String lastError,
                boolean networkEnabled, String networkInterface, int networkPort,
                String networkEndpoint, boolean networkRunning, String networkError,
                long networkRequests, long networkRejected) {
            this.enabled = enabled; this.running = running;
            this.localAccess = localAccess; this.networkAccess = networkAccess;
            this.endpoint = endpoint; this.connections = connections;
            this.requests = requests; this.rejected = rejected; this.lastError = lastError;
            this.networkEnabled = networkEnabled; this.networkInterface = networkInterface;
            this.networkPort = networkPort; this.networkEndpoint = networkEndpoint;
            this.networkRunning = networkRunning; this.networkError = networkError;
            this.networkRequests = networkRequests; this.networkRejected = networkRejected;
        }

        JSONObject networkJson() throws JSONException {
            return new JSONObject().put("enabled", networkEnabled).put("running", networkRunning)
                    .put("interface", networkInterface).put("port", networkPort)
                    .put("endpoint", networkEndpoint).put("encrypted", false)
                    .put("lastError", networkError).put("requests", networkRequests)
                    .put("rejected", networkRejected);
        }

        static Snapshot inactive() {
            return new Snapshot(false, false, new McpAccessPolicy(java.util.Set.of()),
                    new McpAccessPolicy(java.util.Set.of()), "", 0, 0, 0, "",
                    false, "", MagicDeskMcpPreferences.PORT, "", false, "", 0, 0);
        }
    }
}
