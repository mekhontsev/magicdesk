package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FileManagerImportControllerTest {
    @Test
    public void validProviderNameIsPreserved() {
        assertEquals("report.txt",
                FileManagerImportController.safeName("report.txt"));
    }

    @Test
    public void invalidProviderNameUsesSafeFallback() {
        assertEquals("Imported file",
                FileManagerImportController.safeName("../private.txt"));
    }
}
