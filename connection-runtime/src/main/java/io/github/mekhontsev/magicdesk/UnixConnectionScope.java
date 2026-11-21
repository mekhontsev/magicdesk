package io.github.mekhontsev.magicdesk;

import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Revokes admitted streams without reading or proxying their protocol traffic. */
public final class UnixConnectionScope implements Closeable {
    private final MessageQueue queue;
    private final Set<Connection> connections = new HashSet<>();
    private boolean closed;

    public UnixConnectionScope(MessageQueue queue) { this.queue = java.util.Objects.requireNonNull(queue); }

    public synchronized Closeable retain(ParcelFileDescriptor offered) throws IOException {
        if (closed) throw new IOException("Unix connection scope is closed");
        if (connections.size() >= 1024) throw new IOException("Too many live Unix connections");
        var connection = new Connection(ParcelFileDescriptor.dup(offered.getFileDescriptor()));
        try {
            connections.add(connection);
            // EVENT_WAIT: kernel peer hangup only; ordinary readable/writable traffic never wakes this observer.
            queue.addOnFileDescriptorEventListener(connection.fd.getFileDescriptor(),
                    MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR, (fd, events) -> {
                        connection.release(false);
                        return 0;
                    });
            return connection;
        } catch (RuntimeException error) { connection.release(true); throw error; }
    }

    public synchronized int size() { return connections.size(); }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (var connection : Set.copyOf(connections)) connection.release(true);
        }
    }

    private final class Connection implements Closeable {
        final ParcelFileDescriptor fd;
        Connection(ParcelFileDescriptor fd) { this.fd = fd; }
        @Override public void close() { release(true); }
        void release(boolean revoke) {
            synchronized (UnixConnectionScope.this) {
                if (!connections.remove(this)) return;
                queue.removeOnFileDescriptorEventListener(fd.getFileDescriptor());
                if (revoke) try { Os.shutdown(fd.getFileDescriptor(), OsConstants.SHUT_RDWR); }
                catch (android.system.ErrnoException ignored) { }
                try { fd.close(); } catch (IOException ignored) { }
            }
        }
    }
}
