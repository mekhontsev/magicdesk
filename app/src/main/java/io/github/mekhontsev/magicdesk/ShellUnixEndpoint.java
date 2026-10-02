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
    private final IBinder.DeathRecipient died = this::release;
    private volatile boolean closed;
    private long offered, acknowledged;

    ShellUnixEndpoint(String name, int uid, int ioTimeoutMillis, IUnixConnectionReceiver receiver) throws IOException {
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
        try { new Thread(this::accept, "MagicDeskUnixAdmission").start(); }
        catch (RuntimeException | Error error) { release(); throw error; }
    }

    private void accept() {
        try {
            while (!closed) {
                // EVENT_WAIT: incoming kernel connection; owner death/close shuts down the listener.
                try (var client = listener.accept()) {
                    if (closed) break;
                    if (client.getPeerCredentials().getUid() != clientUid) continue;
                    // Configure under the socket owner's SELinux identity, before handing off its FD.
                    client.setSoTimeout(ioTimeoutMillis);
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
    private void release() {
        synchronized (state) {
            if (closed) return;
            closed = true;
            state.notifyAll();
        }
        receiver.asBinder().unlinkToDeath(died, 0);
        try { Os.shutdown(listener.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (android.system.ErrnoException ignored) { }
        try { listener.close(); } catch (IOException ignored) { }
    }
}
