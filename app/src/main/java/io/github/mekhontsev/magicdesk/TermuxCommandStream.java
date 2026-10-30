package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** The same pipe relay as shell commands, bootstrapped through the selected Termux endpoint. */
final class TermuxCommandStream implements CommandStream {
    private final Context context;
    private final TermuxIntegration.Endpoint endpoint;
    private ServerSocket server;
    private Socket connecting, socket;
    private boolean closed;

    TermuxCommandStream(Context context, TermuxIntegration.Endpoint endpoint) {
        this.context = context.getApplicationContext(); this.endpoint = endpoint;
    }

    private Socket connect() throws IOException {
        synchronized (this) {
            if (closed) throw new IOException("Command channel is closed");
            if (socket != null) return socket;
        }
        byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce);
        String token = java.util.HexFormat.of().formatHex(nonce);
        long deadline = android.os.SystemClock.uptimeMillis() + 15000;
        try (ServerSocket listener = new ServerSocket()) {
            synchronized (this) {
                if (closed) throw new IOException("Command channel is closed");
                server = listener;
            }
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
            var current = TermuxIntegration.inspect(context);
            current.requireAvailable();
            if (current.uid != endpoint.uid || !current.packageName.equals(endpoint.packageName))
                throw new IOException("Termux identity changed");
            TermuxIntegration.runCommandBridge(context, endpoint, listener.getLocalPort(), token,
                    TermuxPtyBridgeLauncher.path(context));
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                int remaining = (int) Math.max(1, deadline - android.os.SystemClock.uptimeMillis());
                listener.setSoTimeout(remaining);
                // EVENT_WAIT: authenticated relay connection; expiry fails startup, never retries the command.
                Socket peer = listener.accept();
                synchronized (this) {
                    if (closed) { peer.close(); throw new IOException("Command channel is closed"); }
                    connecting = peer;
                }
                boolean accepted = false;
                try {
                    peer.setSoTimeout(Math.min(1000, remaining));
                    var input = new DataInputStream(peer.getInputStream());
                    if (input.readUnsignedByte() != 17 || input.readInt() != 64) continue;
                    byte[] hello = new byte[64]; input.readFully(hello);
                    if (!MessageDigest.isEqual(hello, token.getBytes(StandardCharsets.US_ASCII))) continue;
                    peer.setSoTimeout(0); peer.setTcpNoDelay(true);
                    synchronized (this) {
                        if (closed) throw new IOException("Command channel is closed");
                        socket = peer; accepted = true;
                    }
                    return peer;
                } catch (IOException error) {
                    synchronized (this) { if (closed) throw error; }
                } finally {
                    synchronized (this) { connecting = null; }
                    if (!accepted) peer.close();
                }
            }
            throw new IOException("Termux command connection timed out");
        } finally { synchronized (this) { server = null; } }
    }

    @Override public InputStream inputStream() throws IOException { return connect().getInputStream(); }
    @Override public void writeLine(String line) throws IOException {
        var output = connect().getOutputStream();
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }
    @Override public void close() {
        java.io.Closeable[] resources;
        synchronized (this) {
            closed = true; resources = new java.io.Closeable[]{server, connecting, socket};
            server = null; connecting = null; socket = null;
        }
        for (var resource : resources) if (resource != null) try { resource.close(); } catch (IOException ignored) { }
    }
}
