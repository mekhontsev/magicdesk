package io.github.mekhontsev.magicdesk.wayland;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class WaylandClientLaunch implements AutoCloseable {
    private final ParcelFileDescriptor connection;
    private final ClientAdmission admission;
    private final String address = "magicdesk-wayland-" + UUID.randomUUID();
    private final LocalServerSocket server;
    private final AtomicReference<LocalSocket> accepted = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object transferLock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CompletableFuture<Void> transferred = new CompletableFuture<>();
    private final Runnable deadline = this::expire;

    public WaylandClientLaunch(ParcelFileDescriptor connection, int clientUid) throws IOException {
        this.connection = java.util.Objects.requireNonNull(connection);
        try {
            admission = new ClientAdmission(clientUid);
            server = new LocalServerSocket(address);
        } catch (IOException | RuntimeException error) {
            connection.close();
            throw error;
        }
        handler.postDelayed(deadline, 10_000);
        Thread worker = new Thread(this::serve, "WaylandClientTransfer");
        worker.setDaemon(true);
        worker.start();
    }

    public List<String> arguments(String helper, String executable, String... arguments) {
        if (closed.get()) throw new IllegalStateException("Wayland launch channel is closed");
        if (helper == null || !helper.startsWith("/") || executable == null || !executable.startsWith("/"))
            throw new IllegalArgumentException("Client executable paths must be absolute");
        ArrayList<String> result = new ArrayList<>(List.of(helper, address, admission.token,
                Integer.toString(Process.myUid()), executable));
        result.addAll(List.of(arguments));
        return List.copyOf(result);
    }

    public CompletionStage<Void> transferred() { return transferred.minimalCompletionStage(); }

    private void serve() {
        try (LocalSocket socket = server.accept()) {
            accepted.set(socket);
            if (closed.get()) throw new IOException("Wayland launch channel is closed");
            socket.setSoTimeout(10_000);
            int uid = socket.getPeerCredentials().getUid();
            byte[] nonce = socket.getInputStream().readNBytes(64);
            if (!admission.accept(uid, nonce)) throw new SecurityException("Wayland client admission rejected");
            synchronized (transferLock) {
                if (closed.get()) throw new IOException("Wayland launch channel is closed");
                socket.setFileDescriptorsForSend(new FileDescriptor[]{connection.getFileDescriptor()});
                socket.getOutputStream().write(1);
                socket.setFileDescriptorsForSend(null);
            }
            finish(null);
        } catch (IOException | RuntimeException error) {
            finish(error);
        } finally { close(); }
    }

    private void expire() {
        finish(new java.util.concurrent.TimeoutException("Wayland FD handoff deadline expired"));
    }

    @Override public void close() {
        finish(new IOException("Wayland launch channel is closed"));
    }

    private void finish(Throwable error) {
        if (!closed.compareAndSet(false, true)) return;
        handler.removeCallbacks(deadline);
        try { Os.shutdown(server.getFileDescriptor(), OsConstants.SHUT_RDWR); }
        catch (ErrnoException ignored) { }
        try { server.close(); } catch (IOException ignored) { }
        LocalSocket socket = accepted.getAndSet(null);
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        synchronized (transferLock) {
            try { connection.close(); } catch (IOException ignored) { }
        }
        if (error == null) transferred.complete(null);
        else transferred.completeExceptionally(error);
    }
}