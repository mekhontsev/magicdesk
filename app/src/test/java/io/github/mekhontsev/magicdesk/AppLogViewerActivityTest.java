package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppLogViewerActivityTest {
    @Test
    public void pendingTextKeepsTheNewestTranscriptTail() {
        final StringBuilder pending = new StringBuilder();
        AppLogViewerActivity.appendPendingText(pending, "first\n", 12);
        AppLogViewerActivity.appendPendingText(pending, "second\n", 12);
        assertEquals("irst\nsecond\n", pending.toString());
        AppLogViewerActivity.appendPendingText(pending, "0123456789abcdef", 12);
        assertEquals("456789abcdef", pending.toString());
    }

    @Test
    public void aBusyUiCannotAccumulateUnboundedPendingText() {
        final StringBuilder pending = new StringBuilder();
        for (int index = 0; index < 10_000; index++) {
            AppLogViewerActivity.appendPendingText(pending, "log line\n", 100);
            assertTrue(pending.length() <= 100);
        }
        assertTrue(pending.toString().endsWith("log line\n"));
    }

    @Test
    public void clippingExistingTextDoesNotSplitASurrogatePair() {
        final StringBuilder pending = new StringBuilder("a\uD83D\uDE00b");
        AppLogViewerActivity.appendPendingText(pending, "cd", 4);
        assertEquals("bcd", pending.toString());
    }

    @Test
    public void clippingAnOversizedChunkDoesNotSplitASurrogatePair() {
        final StringBuilder pending = new StringBuilder("old");
        AppLogViewerActivity.appendPendingText(pending, "a\uD83D\uDE00bc", 3);
        assertEquals("bc", pending.toString());
    }

    @Test
    public void pendingTextRequiresPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () ->
                AppLogViewerActivity.appendPendingText(new StringBuilder(), "x", 0));
    }

    @Test
    public void clearDiscardsQueuedOutputBeforeClearingTheView() throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/AppLogViewerActivity.java"));
        final String clear = source.substring(source.indexOf("private void clearOutput()"),
                source.indexOf("private void drainPending()"));
        assertTrue(source.contains("view -> clearOutput()"));
        assertTrue(clear.contains("synchronized (mPendingLock)"));
        assertTrue(clear.indexOf("mPending.setLength(0)")
                < clear.indexOf("mOutput.setText(\"\")"));
    }
}
