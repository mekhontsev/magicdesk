package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppLogViewerStreamOwnershipTest {
    @Test
    public void theWorkerOwnsTheHandleAcrossEofAndReadFailure() throws IOException {
        final String source = source();
        final String start = between(source, "private void startStream()", "private int packageUid()");
        assertTrue(start.contains("try (ShellStreamHandle stream = ShellAccess.openOwnedStream("));
        assertTrue(start.contains("readStream(stream);\n                }\n            } catch"));
    }

    @Test
    public void stoppingAndAdoptingAStreamShareTheGenerationLock() throws IOException {
        final String source = source();
        final String start = between(source, "private void startStream()", "private int packageUid()");
        final String stop = between(source, "private void closeStream()", "private void updateToggle()");
        assertTrue(start.contains("synchronized (mStreamLock)"));
        assertTrue(start.indexOf("generation != mStreamGeneration.get()")
                < start.indexOf("mStream = stream;"));
        assertTrue(stop.contains("synchronized (mStreamLock)"));
        assertTrue(stop.contains("mStreamGeneration.incrementAndGet();"));
        assertTrue(stop.contains("mStream = null;"));
        assertTrue(stop.indexOf("mStream = null;") < stop.indexOf("stream.close();"));
    }

    @Test
    public void anOldReaderCannotClearANewerStream() throws IOException {
        final String completion = between(source(), "private void readStream(", "private void enqueue(");
        assertTrue(completion.contains("if (mStream == stream)"));
    }

    private static String source() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/AppLogViewerActivity.java"));
    }

    private static String between(final String source, final String start, final String end) {
        final int first = source.indexOf(start);
        final int last = source.indexOf(end, first + start.length());
        assertTrue("method boundaries exist", first >= 0 && last > first);
        return source.substring(first, last);
    }
}
