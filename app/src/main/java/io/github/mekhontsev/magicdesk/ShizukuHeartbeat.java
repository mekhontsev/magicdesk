package io.github.mekhontsev.magicdesk;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sends the common fail-open heartbeat for long-lived Shizuku streams. */
final class ShizukuHeartbeat implements Closeable {
    interface FailureHandler {
        void onFailure(IOException error);
    }

    private static final long INTERVAL_MILLIS = 1_000L;
    private static final String LINE = "ping";

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final List<ShizukuAccess.StreamHandle> mStreams;
    private final FailureHandler mFailureHandler;
    private final Thread mThread;

    private ShizukuHeartbeat(
            final String threadName,
            final FailureHandler failureHandler,
            final ShizukuAccess.StreamHandle[] streams) {
        mStreams = Arrays.asList(streams);
        mFailureHandler = failureHandler;
        mThread = new Thread(this::run, threadName);
        mThread.setDaemon(true);
    }

    static ShizukuHeartbeat start(
            final String threadName,
            final FailureHandler failureHandler,
            final ShizukuAccess.StreamHandle... streams) {
        if (streams == null || streams.length == 0) {
            throw new IllegalArgumentException("at least one stream is required");
        }
        final ShizukuHeartbeat heartbeat = new ShizukuHeartbeat(
                threadName, failureHandler, streams.clone());
        heartbeat.mThread.start();
        return heartbeat;
    }

    @Override
    public void close() {
        if (mClosed.compareAndSet(false, true)) {
            mThread.interrupt();
        }
    }

    private void run() {
        while (!mClosed.get()) {
            try {
                for (final ShizukuAccess.StreamHandle stream : mStreams) {
                    stream.writeLine(LINE);
                }
                Thread.sleep(INTERVAL_MILLIS);
            } catch (IOException error) {
                if (!mClosed.get() && mFailureHandler != null) {
                    mFailureHandler.onFailure(error);
                }
                return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
