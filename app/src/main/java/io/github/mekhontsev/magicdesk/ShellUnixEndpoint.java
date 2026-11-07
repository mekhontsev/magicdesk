package io.github.mekhontsev.magicdesk;

import android.net.LocalServerSocket;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;

/** Executor-owned admission only: accepted sockets leave this process before protocol IO. */
final class ShellUnixEndpoint extends IShellUnixEndpoint.Stub {
    private final LocalServerSocket listener;
    private final IUnixConnectionReceiver receiver;
    private final int ownerUid = Binder.getCallingUid();
    private final int clientUid;
    private final int ioTimeoutMillis;
    private final Object state = new Object();
    private final Object handoff = new Object();
    private android.net.LocalServerSocket fileListener;
    private java.nio.file.Path fileDirectory;
    private final IBinder.DeathRecipient died = this::release;
    private volatile boolean closed;
    private long offered, acknowledged;
    private final java.nio.file.Path temporaryDirectory;

    ShellUnixEndpoint(String name, int uid, int ioTimeoutMillis, IUnixConnectionReceiver receiver) throws IOException {
        this(name, uid, ioTimeoutMillis, receiver, uid == 0 || uid == 2000
                ? RuntimeDataPaths.privileged(uid).temporary()
                : java.nio.file.Path.of(java.util.Objects.requireNonNull(System.getenv("TMPDIR"))));
    }

    ShellUnixEndpoint(String name, int uid, int ioTimeoutMillis, IUnixConnectionReceiver receiver,
            java.nio.file.Path temporaryDirectory) throws IOException {
        this.temporaryDirectory = temporaryDirectory;
        if (ioTimeoutMillis < 0 || ioTimeoutMillis > 60000) throw new IllegalArgumentException("Invalid socket IO bound");
        this.ioTimeoutMillis = ioTimeoutMillis;
        if (Os.getuid() != uid) throw new SecurityException("The command service identity changed");
        if (name == null || !name.matches("magicdesk-[a-z0-9-]{16,80}"))
            throw new IllegalArgumentException("Invalid Unix endpoint name");
        this.receiver = java.util.Objects.requireNonNull(receiver);
        clientUid = uid;
        listener = new LocalServerSocket(name);
        try { receiver.asBinder().linkToDeath(died, 0); }
        catch (RemoteException error) { listener.close(); throw new IOException("Endpoint owner is gone", error); }
        try { new Thread(() -> accept(listener, false), "MagicDeskUnixAdmission").start(); }
        catch (RuntimeException | Error error) { release(); throw error; }
    }

    @Override public String filesystemPath() {
        checkOwner();
        synchronized (state) {
            if (closed) throw new IllegalStateException("Unix endpoint closed");
            if (fileDirectory != null) return fileDirectory.resolve("socket").toString();
            java.nio.file.Path directory = null;
            try {
                java.nio.file.Files.createDirectories(temporaryDirectory);
                directory = java.nio.file.Files.createTempDirectory(temporaryDirectory, ".md-unix-");
                Os.chmod(directory.toString(), 0700);
                String path = directory.resolve("socket").toString();
                if (path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 108)
                    throw new IOException("Unix endpoint path too long");
                try (var socket = new android.net.LocalSocket()) {
                    socket.bind(new android.net.LocalSocketAddress(path, android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                    var descriptor = Os.dup(socket.getFileDescriptor());
                    try { fileListener = new android.net.LocalServerSocket(descriptor); }
                    catch (IOException | RuntimeException error) { Os.close(descriptor); throw error; }
                }
                // Root may explicitly bind this socket (not its private parent) into a chroot for a guest user.
                if (clientUid == 0) Os.chmod(path, 0666);
                fileDirectory = directory;
                var selected = fileListener;
                new Thread(() -> accept(selected, clientUid == 0), "MagicDeskUnixFileAdmission").start();
                return path;
            } catch (IOException | android.system.ErrnoException | RuntimeException error) {
                if (fileListener != null) try { fileListener.close(); } catch (IOException ignored) { }
                fileListener = null;
                fileDirectory = null;
                if (directory != null) {
                    try { java.nio.file.Files.deleteIfExists(directory.resolve("socket")); java.nio.file.Files.deleteIfExists(directory); }
                    catch (IOException ignored) { }
                }
                throw new IllegalStateException("Cannot create filesystem endpoint", error);
            }
        }
    }

    private void accept(LocalServerSocket selected, boolean delegatedRootSocket) {
        try {
            while (!closed) {
                // EVENT_WAIT: incoming kernel connection; owner death/close shuts down the listener.
                try (var client = selected.accept()) {
                    if (closed) break;
                    if (client.getPeerCredentials().getUid() != clientUid && !delegatedRootSocket) continue;
                    // Configure under the socket owner's SELinux identity, before handing off its FD.
                    client.setSoTimeout(ioTimeoutMillis);
                    synchronized (handoff) {
                    final long serial;
                    synchronized (state) { serial = ++offered; }
                    try (var descriptor = ParcelFileDescriptor.dup(client.getFileDescriptor())) {
                        receiver.accepted(serial, descriptor);
                    }
                    // EVENT_WAIT: exact Binder FD handoff acknowledgement; expiry closes admission.
                    long deadline = SystemClock.uptimeMillis() + 10000;
                    synchronized (state) {
                        while (!closed && acknowledged != serial) {
                            long remaining = deadline - SystemClock.uptimeMillis();
                            if (remaining <= 0) throw new IOException("Unix connection handoff timed out");
                            EventDrivenWaits.await(state, EventDrivenWaits.Reason.UNIX_CONNECTION_HANDOFF, remaining);
                        }
                    }
                    }
                }
            }
        } catch (IOException | RemoteException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!closed) try { receiver.failed(ShellAccess.usefulMessage(error)); } catch (RemoteException ignored) { }
        } finally { release(); }
    }

    @Override public void acknowledge(long serial) {
        checkOwner();
        synchronized (state) {
            if (!closed && offered == serial) { acknowledged = serial; state.notifyAll(); }
        }
    }

    @Override public void close() { checkOwner(); release(); }
    private void checkOwner() {
        if (Binder.getCallingUid() != ownerUid) throw new SecurityException("Wrong Unix endpoint owner");
    }
    void release() {
        synchronized (state) {
            if (closed) return;
            closed = true;
            state.notifyAll();
        }
        receiver.asBinder().unlinkToDeath(died, 0);
        try { Os.shutdown(listener.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (android.system.ErrnoException ignored) { }
        try { listener.close(); } catch (IOException ignored) { }
        if (fileListener != null) {
            try { Os.shutdown(fileListener.getFileDescriptor(), OsConstants.SHUT_RDWR); }
            catch (android.system.ErrnoException ignored) { }
            try { fileListener.close(); } catch (IOException ignored) { }
        }
        if (fileDirectory != null) {
            try { java.nio.file.Files.deleteIfExists(fileDirectory.resolve("socket")); java.nio.file.Files.deleteIfExists(fileDirectory); }
            catch (IOException ignored) { }
        }
    }
}
