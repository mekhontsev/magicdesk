package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

/** Shared bounded transfer and cancellation boundary; streams belong to callers. */
final class ContentStreamCopy {
    private ContentStreamCopy() {
    }

    static void checkCancelled(final BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted()
                || (cancelled != null && cancelled.getAsBoolean())) {
            throw new InterruptedIOException("content transfer cancelled");
        }
    }

    static void copy(
            final InputStream input, final OutputStream output,
            final BooleanSupplier cancelled) throws IOException {
        copy(input, output, cancelled, Long.MAX_VALUE);
    }

    static long copy(
            final InputStream input, final OutputStream output,
            final BooleanSupplier cancelled, final long maximumBytes) throws IOException {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("negative content size limit");
        }
        final byte[] buffer = new byte[64 * 1024];
        long total = 0;
        while (true) {
            checkCancelled(cancelled);
            final long remaining = maximumBytes - total;
            // Read at most one byte past the limit to distinguish exact size from overflow.
            final int length = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
            final int count = input.read(buffer, 0, length);
            checkCancelled(cancelled);
            if (count < 0) {
                return total;
            }
            if (count > remaining) {
                throw new IOException("content exceeds byte limit: " + maximumBytes);
            }
            output.write(buffer, 0, count);
            total += count;
        }
    }
}
