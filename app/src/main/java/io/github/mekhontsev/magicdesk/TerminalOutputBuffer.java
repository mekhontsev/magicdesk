package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/** Bounded PTY-to-UI handoff: a busy UI applies backpressure, not output loss. */
final class TerminalOutputBuffer implements AutoCloseable {
    private final int mLimit;
    private final ByteArrayOutputStream mOutput = new ByteArrayOutputStream();
    private boolean mClosed;

    TerminalOutputBuffer(final int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("terminal output limit must be positive");
        }
        mLimit = limit;
    }

    synchronized boolean append(final byte[] data, final int count)
            throws InterruptedException {
        Objects.checkFromIndexSize(0, count, data.length);
        if (count > mLimit) {
            throw new IllegalArgumentException("terminal output chunk exceeds capacity");
        }
        while (!mClosed && count > mLimit - mOutput.size()) {
            EventDrivenWaits.await(this, EventDrivenWaits.Reason.TERMINAL_OUTPUT_DRAIN);
        }
        if (mClosed || count == 0) {
            return false;
        }
        final boolean needsDrain = mOutput.size() == 0;
        mOutput.write(data, 0, count);
        return needsDrain;
    }

    synchronized byte[] drain() {
        final byte[] output = mOutput.toByteArray();
        mOutput.reset();
        notifyAll();
        return output;
    }

    @Override
    public synchronized void close() {
        mClosed = true;
        mOutput.reset();
        notifyAll();
    }
}
