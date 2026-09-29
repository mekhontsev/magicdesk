package io.github.mekhontsev.magicdesk.hosted;

import android.os.Handler;
import android.os.ParcelFileDescriptor;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Bounded transfer from Binder to a protocol loop. The native consumer owns each detached FD. */
public final class HostedSocketAdmission implements Closeable {
    private final Handler handler;
    private final BooleanSupplier ready;
    private final IntConsumer accept;
    private final HashSet<ParcelFileDescriptor> pending = new HashSet<>();
    private boolean closed;

    public HostedSocketAdmission(Handler handler, BooleanSupplier ready, IntConsumer accept) {
        this.handler = handler; this.ready = ready; this.accept = accept;
    }

    public void offer(ParcelFileDescriptor socket) {
        java.util.Objects.requireNonNull(socket);
        synchronized (pending) {
            if (closed || pending.size() >= 32) { discard(socket); return; }
            pending.add(socket);
        }
        if (!handler.post(() -> {
            synchronized (pending) { if (!pending.remove(socket)) return; }
            try (socket) {
                if (ready.getAsBoolean()) accept.accept(socket.detachFd());
            } catch (IOException ignored) { }
        })) {
            synchronized (pending) { if (pending.remove(socket)) discard(socket); }
        }
    }

    @Override public void close() {
        synchronized (pending) {
            closed = true;
            for (var socket : pending) discard(socket);
            pending.clear();
        }
    }

    public static void discard(ParcelFileDescriptor socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }
}
