package io.github.mekhontsev.magicdesk.hosted;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Session-scoped file descriptors opened by the guest, without a host-root path resolver. */
final class GuestFileBridge implements Closeable {
    private final LocalServerSocket listener;
    private final byte[] token;
    private final Object requests = new Object();
    private volatile LocalSocket pending, connection;
    private volatile boolean closed;

    GuestFileBridge(String endpoint, String secret) throws IOException {
        token = secret.getBytes(StandardCharsets.US_ASCII);
        if (token.length != 64) throw new IOException("Invalid guest file authorization");
        listener = new LocalServerSocket(endpoint);
        new Thread(this::accept, "GuestFiles-admission").start();
    }

    private void accept() {
        try {
            while (!closed) {
                LocalSocket candidate = listener.accept();
                pending = candidate;
                boolean retained = false;
                try {
                    // EVENT_WAIT: guest authorization/file-open reply; expiry rejects or disconnects the peer.
                    candidate.setSoTimeout(10_000);
                    if (closed) return;
                    byte[] supplied = candidate.getInputStream().readNBytes(64);
                    if (!MessageDigest.isEqual(token, supplied)) continue;
                    connection = candidate;
                    candidate.getOutputStream().write(0);
                    retained = true;
                    return;
                } catch (IOException ignored) {
                    // A failed admission cannot change an already established session.
                } finally {
                    pending = null;
                    if (!retained) { if (connection == candidate) connection = null; candidate.close(); }
                }
            }
        } catch (IOException ignored) { }
        finally { try { listener.close(); } catch (IOException ignored) { } }
    }

    ParcelFileDescriptor open(String path) throws IOException {
        byte[] encoded = path.getBytes(StandardCharsets.UTF_8);
        if (!path.startsWith("/") || path.indexOf('\0') >= 0 || encoded.length > 4096)
            throw new IOException("Invalid guest file path");
        synchronized (requests) {
            LocalSocket socket = connection;
            if (closed || socket == null) throw new IOException("Guest file bridge is not connected");
            try {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeInt(encoded.length);
                output.write(encoded);
                int status = socket.getInputStream().read();
                FileDescriptor[] descriptors = socket.getAncillaryFileDescriptors();
                try {
                    if (status < 0) throw new IOException("Guest file bridge disconnected");
                    if (status != 0) throw new GuestFileException("Guest cannot open file (errno " + status + ")");
                    if (descriptors == null || descriptors.length != 1) throw new IOException("Missing guest file descriptor");
                    return ParcelFileDescriptor.dup(descriptors[0]);
                } finally {
                    if (descriptors != null) for (FileDescriptor fd : descriptors)
                        try { Os.close(fd); } catch (android.system.ErrnoException ignored) { }
                }
            } catch (GuestFileException error) { throw error; }
            catch (IOException error) { close(); throw error; }
        }
    }

    private static final class GuestFileException extends IOException {
        GuestFileException(String message) { super(message); }
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        disconnect(pending);
        disconnect(connection);
    }

    private static void disconnect(LocalSocket socket) {
        if (socket == null) return;
        try { socket.shutdownInput(); } catch (IOException ignored) { }
        try { socket.shutdownOutput(); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }
}
