package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FileManagerStatusTest {
    @Test
    public void summaryIsVisibleWithoutAnOperationMessage() {
        final FileManagerStatus status = new FileManagerStatus();
        assertEquals("", status.text());
        status.summary("10 files");
        assertEquals("10 files", status.text());
    }

    @Test
    public void automaticRefreshDoesNotEraseOperationResult() {
        final FileManagerStatus status = new FileManagerStatus();
        status.summary("10 files");
        status.message("Imported 2 files; 1 failed");
        status.summary("0 files");
        status.summary("12 files");
        assertEquals("Imported 2 files; 1 failed", status.text());
        status.clearMessage();
        assertEquals("12 files", status.text());
    }

    @Test
    public void explicitRefreshCanShowSearchProgressAfterClearingMessage() {
        final FileManagerStatus status = new FileManagerStatus();
        status.message("Previous search failed");
        status.clearMessage();
        status.summary("Searching");
        assertEquals("Searching", status.text());
        status.summary("20 results");
        assertEquals("20 results", status.text());
    }

    @Test
    public void newMessageReplacesOldMessageAndNullRestoresSummary() {
        final FileManagerStatus status = new FileManagerStatus();
        status.summary("5 files");
        status.message("Copied path");
        status.message("Operation failed");
        assertEquals("Operation failed", status.text());
        status.message(null);
        assertEquals("5 files", status.text());
        status.summary(null);
        assertEquals("", status.text());
    }

    @Test
    public void windowsDoNotShareStatus() {
        final FileManagerStatus first = new FileManagerStatus();
        final FileManagerStatus second = new FileManagerStatus();
        first.message("Operation failed");
        second.summary("5 files");
        second.clearMessage();
        assertEquals("Operation failed", first.text());
        assertEquals("5 files", second.text());
    }
}
