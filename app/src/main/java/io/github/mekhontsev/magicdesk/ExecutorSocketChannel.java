package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;
import android.system.*;
import java.io.*;

/** IO on an executor-owned socket without changing its SELinux-owned socket options. */
final class ExecutorSocketChannel implements Closeable {
    private final ParcelFileDescriptor socket;
    private final ParcelFileDescriptor[] cancellation;
    private final FileInputStream source;
    final OutputStream output;
    private volatile boolean closed;
    private boolean reading;
    private volatile long readDeadline;
    final InputStream input = new InputStream() {
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            synchronized (ExecutorSocketChannel.this) {
                if (closed) throw new IOException("Executor socket closed");
                if (reading) throw new IOException("Concurrent executor socket read");
                reading = true;
            }
            try {
                var peer = new StructPollfd(); peer.fd = socket.getFileDescriptor(); peer.events = (short)OsConstants.POLLIN;
                var cancel = new StructPollfd(); cancel.fd = cancellation[0].getFileDescriptor(); cancel.events = (short)OsConstants.POLLIN;
                var descriptors = new StructPollfd[]{peer, cancel};
                for (;;) {
                    long remaining = readDeadline == 0 ? -1 : readDeadline - android.os.SystemClock.uptimeMillis();
                    if (readDeadline != 0 && remaining <= 0) throw new java.net.SocketTimeoutException("Executor frame incomplete");
                    // EVENT_WAIT: data, cancellation or an active frame deadline. Idle streams have no timer.
                    try {
                        if (Os.poll(descriptors, (int)Math.min(remaining, Integer.MAX_VALUE)) == 0)
                            throw new java.net.SocketTimeoutException("Executor frame incomplete");
                    }
                    catch (ErrnoException error) { if (error.errno == OsConstants.EINTR) continue; throw error; }
                    if (closed || cancel.revents != 0) throw new IOException("Executor socket closed");
                    if (peer.revents != 0) return source.read(bytes, offset, length);
                }
            } catch (ErrnoException error) { throw new IOException("Executor socket read failed", error); }
            finally {
                synchronized (ExecutorSocketChannel.this) {
                    reading = false;
                    if (closed) { discard(cancellation[0]); discard(socket); }
                }
            }
        }
    };

    ExecutorSocketChannel(ParcelFileDescriptor socket) throws IOException {
        this.socket = socket;
        cancellation = ParcelFileDescriptor.createPipe();
        source = new FileInputStream(socket.getFileDescriptor());
        output = new FileOutputStream(socket.getFileDescriptor());
    }
    void readTimeout(int timeoutMillis) {
        readDeadline = timeoutMillis == 0 ? 0 : android.os.SystemClock.uptimeMillis() + timeoutMillis;
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        discard(cancellation[1]);
        if (!reading) { discard(cancellation[0]); discard(socket); }
    }
    private static void discard(Closeable resource) { try { resource.close(); } catch (IOException ignored) { } }
}
