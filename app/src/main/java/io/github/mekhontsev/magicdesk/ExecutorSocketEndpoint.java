package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Captured executor admission shared by protocol servers; no graphics or protocol proxy. */
final class ExecutorSocketEndpoint implements Closeable {
    interface Sink { void accept(ParcelFileDescriptor socket) throws RemoteException, IOException; }
    final String name;
    private final CompletableFuture<IShellUnixEndpoint> endpoint = new CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final android.os.IBinder.DeathRecipient died;

    ExecutorSocketEndpoint(int uid, Sink sink, Consumer<Throwable> failure) throws IOException {
        this(uid, 0, sink, failure);
    }

    ExecutorSocketEndpoint(int uid, int ioTimeoutMillis, Sink sink, Consumer<Throwable> failure) throws IOException {
        byte[] nonce = new byte[32]; new java.security.SecureRandom().nextBytes(nonce);
        name = "magicdesk-" + java.util.HexFormat.of().formatHex(nonce);
        died = () -> {
            if (!closed.get()) failure.accept(new IOException("Unix endpoint service died"));
            close();
        };
        var receiver = new IUnixConnectionReceiver.Stub() {
            @Override public void accepted(long serial, ParcelFileDescriptor socket) {
                if (Binder.getCallingUid() != uid) {
                    discard(socket);
                    throw new SecurityException("Wrong socket admission identity");
                }
                endpoint.whenComplete((owner, error) -> {
                    try (socket) {
                        if (error != null || closed.get()) return;
                        sink.accept(socket);
                        owner.acknowledge(serial);
                    } catch (IOException | RemoteException | RuntimeException problem) {
                        if (!closed.get()) failure.accept(problem);
                        close();
                    }
                });
            }
            @Override public void failed(String message) {
                if (Binder.getCallingUid() != uid) throw new SecurityException("Wrong endpoint identity");
                if (!closed.get()) failure.accept(new IOException(message));
                close();
            }
        };
        IShellUnixEndpoint owner = null;
        try {
            owner = ShellAccess.openUnixEndpoint(name, uid, ioTimeoutMillis, receiver);
            owner.asBinder().linkToDeath(died, 0);
            endpoint.complete(owner);
        } catch (IOException | RemoteException | RuntimeException error) {
            endpoint.completeExceptionally(error);
            if (owner != null) try { owner.close(); } catch (RemoteException ignored) { }
            throw new IOException("Cannot open executor socket endpoint", error);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        endpoint.thenAccept(owner -> {
            owner.asBinder().unlinkToDeath(died, 0);
            try { owner.close(); } catch (RemoteException ignored) { }
        });
    }

    private static void discard(ParcelFileDescriptor socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
