package io.github.mekhontsev.magicdesk;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;

/** Owns the optional MCP server for exactly one MagicDesk runtime lifetime. */
final class MagicDeskMcpRuntime implements Closeable {
    private static final Object SNAPSHOT_LOCK = new Object();
    private static Snapshot sSnapshot = Snapshot.inactive();
    private static volatile MagicDeskMcpHttpServer sActiveServer;

    private final Context mContext;
    private MagicDeskMcpHttpServer mServer;

    MagicDeskMcpRuntime(final Context context) {
        mContext = context.getApplicationContext();
    }

    synchronized void reconcile() {
        final MagicDeskMcpPreferences.Values settings =
                MagicDeskMcpPreferences.load(mContext);
        if (!settings.enabled || settings.token.isEmpty()) {
            stopServer();
            publish(settings, null, "");
            return;
        }
        if (mServer != null && mServer.snapshot().running) {
            publish(settings, mServer.snapshot(), "");
            return;
        }
        stopServer();
        final MagicDeskMcpHttpServer server = new MagicDeskMcpHttpServer(
                new McpJsonRpcHandler(new MagicDeskMcpBackend(mContext)),
                () -> MagicDeskMcpPreferences.load(mContext).token);
        try {
            server.start(MagicDeskMcpPreferences.HOST,
                    MagicDeskMcpPreferences.PORT);
            mServer = server;
            sActiveServer = server;
            publish(settings, server.snapshot(), "");
        } catch (IOException | RuntimeException error) {
            server.close();
            final String message = ShellAccess.usefulMessage(error);
            publish(settings, null, message);
            CompatibilityDiagnostics.record(
                    "MCP-001",
                    "Could not start the local MCP server",
                    message,
                    error);
        }
    }

    @Override
    public synchronized void close() {
        final MagicDeskMcpPreferences.Values settings =
                MagicDeskMcpPreferences.load(mContext);
        stopServer();
        publish(settings, null, "");
    }

    static Snapshot snapshot() {
        final MagicDeskMcpHttpServer server = sActiveServer;
        synchronized (SNAPSHOT_LOCK) {
            if (server == null) {
                return sSnapshot;
            }
            final MagicDeskMcpHttpServer.Snapshot live = server.snapshot();
            return new Snapshot(
                    sSnapshot.enabled,
                    live.running,
                    sSnapshot.developerTools,
                    sSnapshot.endpoint,
                    live.connections,
                    live.requests,
                    live.rejected,
                    live.lastError.isEmpty()
                            ? sSnapshot.lastError : live.lastError);
        }
    }

    static JSONObject snapshotJson() throws JSONException {
        final Snapshot snapshot = snapshot();
        return new JSONObject()
                .put("enabled", snapshot.enabled)
                .put("running", snapshot.running)
                .put("developerTools", snapshot.developerTools)
                .put("endpoint", snapshot.endpoint)
                .put("connections", snapshot.connections)
                .put("requests", snapshot.requests)
                .put("rejected", snapshot.rejected)
                .put("lastError", snapshot.lastError);
    }

    private void stopServer() {
        if (mServer != null) {
            if (sActiveServer == mServer) {
                sActiveServer = null;
            }
            mServer.close();
            mServer = null;
        }
    }

    private static void publish(
            final MagicDeskMcpPreferences.Values settings,
            final MagicDeskMcpHttpServer.Snapshot server,
            final String error) {
        synchronized (SNAPSHOT_LOCK) {
            sSnapshot = new Snapshot(
                    settings.enabled,
                    server != null && server.running,
                    settings.developerTools,
                    settings.endpoint(),
                    server == null ? 0L : server.connections,
                    server == null ? 0L : server.requests,
                    server == null ? 0L : server.rejected,
                    error == null || error.isEmpty()
                            ? server == null ? "" : server.lastError
                            : error);
        }
    }

    static final class Snapshot {
        final boolean enabled;
        final boolean running;
        final boolean developerTools;
        final String endpoint;
        final long connections;
        final long requests;
        final long rejected;
        final String lastError;

        Snapshot(
                final boolean enabled,
                final boolean running,
                final boolean developerTools,
                final String endpoint,
                final long connections,
                final long requests,
                final long rejected,
                final String lastError) {
            this.enabled = enabled;
            this.running = running;
            this.developerTools = developerTools;
            this.endpoint = endpoint == null ? "" : endpoint;
            this.connections = connections;
            this.requests = requests;
            this.rejected = rejected;
            this.lastError = lastError == null ? "" : lastError;
        }

        static Snapshot inactive() {
            return new Snapshot(
                    false, false, false, "", 0L, 0L, 0L, "");
        }
    }
}
