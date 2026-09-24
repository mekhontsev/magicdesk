package io.github.mekhontsev.magicdesk.wayland;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.system.OsConstants;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** App-owned readiness/lifetime channel; the captured root executor owns the native process. */
public final class WaylandBroker implements Closeable {
    private final LocalServerSocket listener;
    private final String endpoint = "magicdesk-wayland-" + UUID.randomUUID();
    private final byte[] token = new byte[32];
    private final CompletableFuture<String> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable deadline = () -> finish(new IOException("Wayland broker admission deadline expired"));
    private LocalSocket connection;
    private boolean closed;

    public WaylandBroker() throws IOException {
        new SecureRandom().nextBytes(token);
        listener = new LocalServerSocket(endpoint);
        // EVENT_WAIT: authenticated broker connection and actual buffer receipt; expiry cancels startup.
        handler.postDelayed(deadline, 10_000);
        new Thread(this::accept, "WaylandBroker-admission").start();
    }

    public List<String> arguments(String helper, String socket, int serverUid, String bufferLabel) {
        if (serverUid <= 0 || helper == null || !helper.startsWith("/") || socket == null || !socket.startsWith("/")
                || bufferLabel == null || bufferLabel.isBlank() || bufferLabel.length() >= 512)
            throw new IllegalArgumentException("Missing Wayland broker endpoint identity");
        return List.of(helper, "--broker", socket, Integer.toString(serverUid), bufferLabel,
                endpoint, java.util.HexFormat.of().formatHex(token));
    }

    public CompletionStage<String> ready() { return ready.minimalCompletionStage(); }
    public CompletionStage<Void> ended() { return ended.minimalCompletionStage(); }

    private void accept() {
        try {
            LocalSocket candidate = listener.accept();
            synchronized (this) {
                if (closed) { candidate.close(); return; }
                connection = candidate;
            }
            if (candidate.getPeerCredentials().getUid() != 0) throw new IOException("Not the selected root broker");
            // EVENT_WAIT: complete authenticated startup message; expiry rejects this broker.
            candidate.setSoTimeout(10_000);
            var input = candidate.getInputStream();
            byte[] expected = java.util.HexFormat.of().formatHex(token).getBytes(StandardCharsets.US_ASCII);
            int first = input.read();
            FileDescriptor[] descriptors = candidate.getAncillaryFileDescriptors();
            try {
                byte[] received = new byte[expected.length];
                received[0] = (byte)first;
                byte[] rest = input.readNBytes(expected.length - 1);
                System.arraycopy(rest, 0, received, 1, rest.length);
                if (first < 0 || rest.length != expected.length - 1 || !MessageDigest.isEqual(received, expected)
                        || descriptors == null || descriptors.length != 1)
                    throw new IOException("Invalid Wayland broker authorization or memory descriptor");
                byte[] probe = new byte[4];
                if (Os.pread(descriptors[0], probe, 0, probe.length, 0) != probe.length
                        || !java.util.Arrays.equals(probe, new byte[]{'M', 'D', 'W', 'B'}))
                    throw new IOException("Wayland guest shared-memory admission failed");
                long mapped = Os.mmap(0, probe.length, OsConstants.PROT_READ | OsConstants.PROT_WRITE,
                        OsConstants.MAP_SHARED, descriptors[0], 0);
                Os.munmap(mapped, probe.length);
            } finally {
                if (descriptors != null) for (FileDescriptor fd : descriptors) Os.close(fd);
            }
            StringBuilder name = new StringBuilder();
            for (int n = 0; n < 32; ++n) {
                int value = input.read();
                if (value == '\n') break;
                if (value < 0 || value > 127 || n == 31) throw new IOException("Invalid Wayland broker endpoint");
                name.append((char)value);
            }
            if (!name.toString().matches("wayland-[0-9]+")) throw new IOException("Invalid Wayland broker socket");
            candidate.getOutputStream().write(0);
            candidate.setSoTimeout(0);
            handler.removeCallbacks(deadline);
            ready.complete(name.toString());
            // EVENT_WAIT: retained owner channel; EOF is broker loss, explicit close cancels the read.
            input.read();
            throw new IOException("Wayland root broker disconnected");
        } catch (Exception failure) { finish(failure); }
    }

    private void finish(Throwable failure) {
        LocalSocket socket;
        synchronized (this) {
            if (closed) return;
            closed = true; socket = connection; connection = null;
        }
        handler.removeCallbacks(deadline);
        try { Os.shutdown(listener.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (Exception ignored) { }
        try { listener.close(); } catch (IOException ignored) { }
        if (socket != null) {
            try { socket.shutdownInput(); } catch (IOException ignored) { }
            try { socket.shutdownOutput(); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }
        ready.completeExceptionally(failure);
        ended.completeExceptionally(failure);
    }

    @Override public void close() { finish(new IOException("Wayland broker closed")); }
}
