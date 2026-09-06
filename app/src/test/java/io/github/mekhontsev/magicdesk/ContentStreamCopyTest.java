package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public final class ContentStreamCopyTest {
    @Test
    public void cancellationBeforeEmptyInputDoesNotCallProvider() {
        final InputStream input = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("cancelled provider must not be read");
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> ContentStreamCopy.copy(input, new ByteArrayOutputStream(), () -> true));
    }

    @Test
    public void cancellationDuringEofIsNotSuccessfulImport() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final InputStream input = new InputStream() {
            @Override
            public int read() {
                cancelled.set(true);
                return -1;
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> ContentStreamCopy.copy(input, new ByteArrayOutputStream(), cancelled::get));
    }

    @Test
    public void cancellationDuringReadDoesNotWriteThatChunk() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final InputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override
            public int read(final byte[] bytes, final int offset, final int length) {
                cancelled.set(true);
                return super.read(bytes, offset, length);
            }
        };
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThrows(InterruptedIOException.class,
                () -> ContentStreamCopy.copy(input, output, cancelled::get));
        assertEquals(0, output.size());
    }

    @Test
    public void workerInterruptionCancelsEvenWithoutAnExplicitSignal() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> ContentStreamCopy.copy(
                    new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void copiesAllChunksWithoutClosingCallerOwnedStreams() throws Exception {
        final byte[] data = new byte[150_000];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) index;
        }
        final InputStream input = new ByteArrayInputStream(data) {
            @Override
            public void close() {
                throw new AssertionError("stream belongs to caller");
            }
        };
        final ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override
            public void close() {
                throw new AssertionError("stream belongs to caller");
            }
        };

        ContentStreamCopy.copy(input, output, () -> false);

        assertArrayEquals(data, output.toByteArray());
    }

    @Test
    public void exactByteLimitIsAcceptedIncludingEmptyContent() throws Exception {
        for (final int size : new int[] {0, 1, 65536, 65537}) {
            final byte[] bytes = new byte[size];
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertEquals(size, ContentStreamCopy.copy(
                    new ByteArrayInputStream(bytes), output, null, size));
            assertArrayEquals(bytes, output.toByteArray());
        }
    }

    @Test
    public void overflowReadsOnlyOneExtraByteAndNeverWritesPastLimit() {
        for (final int limit : new int[] {0, 1, 65536, 65537}) {
            final byte[] bytes = new byte[limit + 20];
            final ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> ContentStreamCopy.copy(input, output, null, limit));
            assertEquals(19, input.available());
            assertTrue(output.size() <= limit);
        }
    }

    @Test
    public void cancellationAtTheSizeBoundaryIsStillCancellation() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final InputStream input = new ByteArrayInputStream(new byte[]{1}) {
            @Override
            public int read(final byte[] bytes, final int offset, final int length) {
                final int read = super.read(bytes, offset, length);
                if (read < 0) {
                    cancelled.set(true);
                }
                return read;
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> ContentStreamCopy.copy(input, new ByteArrayOutputStream(), cancelled::get, 1));
    }

    @Test
    public void negativeLimitIsRejectedBeforeReading() {
        final InputStream input = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("invalid limit must not read input");
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> ContentStreamCopy.copy(input, new ByteArrayOutputStream(), null, -1));
    }
}
