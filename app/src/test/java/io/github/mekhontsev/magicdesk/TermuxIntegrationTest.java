package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class TermuxIntegrationTest {
    @Test
    public void sessionNameIdentifiesNormalizedDirectory() {
        assertEquals(
                "MagicDesk: /storage/emulated/0/Documents",
                TermuxIntegration.shellNameForDirectory(
                        "/storage/emulated/0/Desktop/../Documents"));
    }

    @Test
    public void sessionNameRejectsRelativeDirectory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TermuxIntegration.shellNameForDirectory("Desktop"));
    }
}
