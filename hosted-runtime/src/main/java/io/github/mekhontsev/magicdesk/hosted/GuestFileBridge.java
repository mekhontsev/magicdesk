package io.github.mekhontsev.magicdesk.hosted;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Session-scoped file descriptors opened by the guest, without a host-root path resolver. */
public final class GuestFileBridge implements Closeable {
    private final LocalServerSocket listener;
    private final java.net.ServerSocket loopback;
    private final byte[] token;
    private final Object requests = new Object();
    private volatile Connection pending, connection;
    private volatile boolean closed;
    private final java.util.concurrent.CompletableFuture<Void> ready = new java.util.concurrent.CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicBoolean offered = new java.util.concurrent.atomic.AtomicBoolean();

    public GuestFileBridge(String endpoint, String secret) throws IOException {
        this(endpoint, secret, false);
    }

    /** Byte-only imports across app UIDs; descriptor export retains Unix admission. */
    public static GuestFileBridge loopback(String secret) throws IOException {
        return new GuestFileBridge("", secret, true);
    }

    public String endpoint() {
        if (loopback == null) throw new IllegalStateException("Not a loopback file channel");
        return "tcp:" + loopback.getLocalPort();
    }

    private GuestFileBridge(String endpoint, String secret, boolean tcp) throws IOException {
        token = secret.getBytes(StandardCharsets.US_ASCII);
        if (token.length != 64) throw new IOException("Invalid guest file authorization");
        listener = endpoint.isEmpty() ? null : new LocalServerSocket(endpoint);
        loopback = tcp ? new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")) : null;
        if (listener != null || loopback != null) new Thread(this::accept, "GuestFiles-admission").start();
    }

    private void accept() {
        try {
            while (!closed) {
                if (loopback != null) try (var candidate = loopback.accept()) {
                    candidate.setTcpNoDelay(true);
                    if (admit(ParcelFileDescriptor.fromSocket(candidate))) return;
                } else try (LocalSocket candidate = listener.accept()) {
                    if (admit(ParcelFileDescriptor.dup(candidate.getFileDescriptor()))) return;
                }
            }
        } catch (IOException ignored) { }
        finally { closeListeners(); }
    }

    public void offer(ParcelFileDescriptor descriptor) {
        if (listener != null || loopback != null) { HostedSocketAdmission.discard(descriptor); throw new IllegalStateException("Local admission owns this bridge"); }
        if (closed || connection != null || !offered.compareAndSet(false, true)) { HostedSocketAdmission.discard(descriptor); return; }
        try {
            new Thread(() -> { try { admit(descriptor); } finally { offered.set(false); } }, "GuestFiles-authorization").start();
        } catch (RuntimeException | Error error) { offered.set(false); HostedSocketAdmission.discard(descriptor); throw error; }
    }

    private boolean admit(ParcelFileDescriptor descriptor) {
        Connection candidate;
        try { candidate = new Connection(descriptor, listener != null || loopback != null); }
        catch (IOException error) { HostedSocketAdmission.discard(descriptor); return false; }
        synchronized (this) {
            if (closed || pending != null || connection != null) { disconnect(candidate); return false; }
            pending = candidate;
        }
        boolean retained = false;
        try {
            // EVENT_WAIT: guest authorization/file reply; socket expiry rejects or disconnects the peer.
            byte[] supplied = candidate.getInputStream().readNBytes(64);
            if (!MessageDigest.isEqual(token, supplied)) return false;
            synchronized (this) {
                if (closed) return false;
                connection = candidate;
            }
            candidate.getOutputStream().write(0);
            retained = true;
            ready.complete(null);
            return true;
        } catch (IOException ignored) { return false; }
        finally {
            synchronized (this) { pending = null; if (!retained && connection == candidate) connection = null; }
            if (!retained) disconnect(candidate);
        }
    }

    public void awaitReady() throws IOException {
        // EVENT_WAIT: authenticated helper admission; expiry fails this attachment channel.
        try { ready.get(15, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) { throw new IOException("File helper did not connect", error); }
    }

    public String directory() throws IOException { return request(6, ".", null); }
    public void mkdir(String relative) throws IOException { request(3, relative, null); }
    public void remove(String relative) throws IOException { request(5, relative, null); }
    public void put(String relative, ParcelFileDescriptor file) throws IOException { request(4, relative, file); }

    private String request(int operation, String path, ParcelFileDescriptor file) throws IOException {
        byte[] encoded = path.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > 4096 || path.indexOf('\0') >= 0)
            throw new IOException("Invalid import path");
        synchronized (requests) {
            Connection socket = connection;
            if (closed || socket == null) throw new IOException("File helper is not connected");
            try {
                var output = new DataOutputStream(socket.getOutputStream());
                output.writeByte(operation); output.writeInt(encoded.length); output.write(encoded);
                var input = new java.io.DataInputStream(socket.getInputStream());
                if (file != null) {
                    long size = file.getStatSize();
                    if (size < 0 || size > 128L * 1024 * 1024) throw new IOException("Invalid import size");
                    output.writeLong(size);
                    if (input.readUnsignedByte() != 0) throw new GuestFileException("Cannot create imported file");
                    sendFileBody(output, file, size);
                }
                if (input.readUnsignedByte() != 0) throw new GuestFileException("File operation failed");
                if (operation != 6) return "";
                int length = input.readInt();
                if (length <= 0 || length > 4096) throw new IOException("Invalid import directory");
                byte[] value = new byte[length]; input.readFully(value);
                String directory = new String(value, StandardCharsets.UTF_8);
                if (!directory.startsWith("/") || directory.indexOf('\0') >= 0) throw new IOException("Invalid import directory");
                return directory;
            } catch (GuestFileException error) { throw error; }
            catch (IOException error) { close(); throw error; }
        }
    }

    private void sendFile(DataOutputStream output, ParcelFileDescriptor source, long size) throws IOException {
        if (size < 0 || size > 128L * 1024 * 1024) throw new IOException("Invalid import size");
        output.writeLong(size);
        sendFileBody(output, source, size);
    }

    private void sendFileBody(DataOutputStream output, ParcelFileDescriptor source, long size) throws IOException {
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(source.getFileDescriptor()))) {
            byte[] bytes = new byte[65536];
            long remaining = size;
            while (remaining > 0) {
                if (closed) throw new IOException("File exchange closed");
                int count = input.read(bytes, 0, (int)Math.min(remaining, bytes.length));
                if (count < 0) throw new IOException("Incomplete guest file transfer");
                output.write(bytes, 0, count); remaining -= count;
            }
            if (input.read() != -1) throw new IOException("Imported file grew during transfer");
        }
    }

    ParcelFileDescriptor open(String path) throws IOException {
        if (loopback != null) throw new IOException("Descriptor export requires Unix admission");
        byte[] encoded = path.getBytes(StandardCharsets.UTF_8);
        if (!path.startsWith("/") || path.indexOf('\0') >= 0 || encoded.length > 4096)
            throw new IOException("Invalid guest file path");
        synchronized (requests) {
            Connection socket = connection;
            if (closed || socket == null) throw new IOException("Guest file bridge is not connected");
            try {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeByte(1);
                output.writeInt(encoded.length);
                output.write(encoded);
                var reply = socket.receive();
                int status = reply.status();
                ParcelFileDescriptor[] descriptors = reply.descriptors();
                try {
                    if (status < 0) throw new IOException("Guest file bridge disconnected");
                    if (status != 0) throw new GuestFileException("Guest cannot open file (errno " + status + ")");
                    if (descriptors == null || descriptors.length != 1) throw new IOException("Missing guest file descriptor");
                    return ParcelFileDescriptor.dup(descriptors[0].getFileDescriptor());
                } finally {
                    if (descriptors != null) for (var fd : descriptors) fd.close();
                }
            } catch (GuestFileException error) { throw error; }
            catch (IOException error) { close(); throw error; }
        }
    }

    String importFile(ParcelFileDescriptor source, String name, long size) throws IOException {
        byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 240) throw new IOException("Imported file name is too long");
        synchronized (requests) {
            Connection socket = connection;
            if (closed || socket == null) throw new IOException("Guest file bridge is not connected");
            try {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeByte(2);
                output.writeInt(encoded.length); output.write(encoded);
                sendFile(output, source, size);
                var response = new java.io.DataInputStream(socket.getInputStream());
                if (response.readUnsignedByte() != 0) throw new IOException("Guest rejected file import");
                int length = response.readInt();
                if (length <= 0 || length > 4096) throw new IOException("Invalid guest import receipt");
                byte[] path = new byte[length]; response.readFully(path);
                String value = new String(path, StandardCharsets.UTF_8);
                if (!value.startsWith("/") || value.indexOf('\0') >= 0) throw new IOException("Invalid guest import path");
                return new java.net.URI("file", null, value, null).toASCIIString();
            } catch (java.net.URISyntaxException error) { close(); throw new IOException("Invalid guest file URI", error); }
            catch (IOException error) { close(); throw error; }
        }
    }

    private static final class GuestFileException extends IOException {
        GuestFileException(String message) { super(message); }
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        ready.completeExceptionally(new IOException("File exchange closed"));
        closeListeners();
        disconnect(pending);
        disconnect(connection);
    }

    private void closeListeners() {
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        if (loopback != null) try { loopback.close(); } catch (IOException ignored) { }
    }

    private static void disconnect(Connection socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    /** Owns an admitted FD through public android.system APIs (also available on API 34). */
    private static final class Connection implements Closeable {
        // Linux UAPI constants omitted from the public OsConstants surface.
        private static final int MSG_CMSG_CLOEXEC = 0x40000000, SCM_RIGHTS = 1;
        final ParcelFileDescriptor descriptor;
        final java.io.InputStream input;
        final java.io.OutputStream output;
        Connection(ParcelFileDescriptor descriptor, boolean local) throws IOException {
            this.descriptor = descriptor;
            if (local) try {
                var timeout = android.system.StructTimeval.fromMillis(10_000);
                Os.setsockoptTimeval(descriptor.getFileDescriptor(), android.system.OsConstants.SOL_SOCKET, android.system.OsConstants.SO_RCVTIMEO, timeout);
                Os.setsockoptTimeval(descriptor.getFileDescriptor(), android.system.OsConstants.SOL_SOCKET, android.system.OsConstants.SO_SNDTIMEO, timeout);
            } catch (android.system.ErrnoException error) { throw new IOException(error); }
            input = new java.io.InputStream() {
                @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) == -1 ? -1 : b[0] & 255; }
                @Override public int read(byte[] b, int offset, int count) throws IOException {
                    if (count == 0) return 0;
                    try { int n = Os.read(descriptor.getFileDescriptor(), b, offset, count); return n == 0 ? -1 : n; }
                    catch (android.system.ErrnoException error) { throw new IOException(error); }
                }
            };
            output = new java.io.OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[] {(byte)b}); }
                @Override public void write(byte[] b, int offset, int count) throws IOException {
                    try { while (count > 0) { int n = Os.write(descriptor.getFileDescriptor(), b, offset, count); if (n == 0) throw new IOException("Socket write made no progress"); offset += n; count -= n; } }
                    catch (android.system.ErrnoException error) { throw new IOException(error); }
                }
            };
        }
        java.io.InputStream getInputStream() { return input; }
        java.io.OutputStream getOutputStream() { return output; }
        record Reply(int status, ParcelFileDescriptor[] descriptors) { }
        Reply receive() throws IOException {
            var data = java.nio.ByteBuffer.allocate(1);
            var message = new android.system.StructMsghdr(null, new java.nio.ByteBuffer[] {data}, null, 0);
            var fds = new java.util.ArrayList<ParcelFileDescriptor>();
            try {
                int count = Os.recvmsg(descriptor.getFileDescriptor(), message, MSG_CMSG_CLOEXEC);
                if (message.msg_control != null) for (var control : message.msg_control) {
                    if (control.cmsg_level != android.system.OsConstants.SOL_SOCKET || control.cmsg_type != SCM_RIGHTS) continue;
                    var bytes = java.nio.ByteBuffer.wrap(control.cmsg_data).order(java.nio.ByteOrder.nativeOrder());
                    while (bytes.remaining() >= 4) fds.add(ParcelFileDescriptor.adoptFd(bytes.getInt()));
                }
                if ((message.msg_flags & android.system.OsConstants.MSG_CTRUNC) != 0) throw new IOException("Truncated guest file descriptors");
                return new Reply(count == 1 ? data.get(0) & 255 : -1, fds.toArray(ParcelFileDescriptor[]::new));
            } catch (android.system.ErrnoException | IOException | RuntimeException error) {
                for (var fd : fds) HostedSocketAdmission.discard(fd);
                throw new IOException("Cannot receive guest file descriptor", error);
            }
        }
        @Override public void close() throws IOException {
            try { Os.shutdown(descriptor.getFileDescriptor(), android.system.OsConstants.SHUT_RDWR); }
            catch (android.system.ErrnoException ignored) { }
            descriptor.close();
        }
    }
}
