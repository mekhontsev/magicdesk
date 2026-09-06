package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ContentUriTransferTest {
    @Test
    public void validProviderNameIsPreserved() {
        assertEquals("report.txt",
                ContentUriTransfer.safeFileName("report.txt"));
    }

    @Test
    public void invalidProviderNameUsesSafeFallback() {
        assertEquals("Imported file",
                ContentUriTransfer.safeFileName("../private.txt"));
    }

    @Test
    public void missingAndMalformedProviderNamesUseTheSameFallback() {
        for (final String invalid : new String[] {null, "", "  ", ".", "..", "a\0b"}) {
            assertEquals("Imported file", ContentUriTransfer.safeFileName(invalid));
        }
        assertEquals("Report.PDF", ContentUriTransfer.safeFileName(" Report.PDF "));
    }
}
