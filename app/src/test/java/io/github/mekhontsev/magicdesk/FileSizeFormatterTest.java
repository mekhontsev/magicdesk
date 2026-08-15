package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FileSizeFormatterTest {
    @Test
    public void formatsBytesWithoutFraction() {
        assertEquals("512 B", FileSizeFormatter.format(512L));
    }

    @Test
    public void formatsLargerUnitsConsistently() {
        assertEquals("1.5 KB", FileSizeFormatter.format(1536L));
        assertEquals("2.0 MB",
                FileSizeFormatter.format(2L * 1024L * 1024L));
    }
}
