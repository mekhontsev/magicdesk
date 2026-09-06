package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class DesktopWebShortcutTest {
    @Test
    public void unicodeUrlNormalizationIsStableAcrossFileRoundTrip() {
        final String url = DesktopWebShortcut.normalizeUrl("https://example.com/\u044f?q=\u0431#\u044f");
        assertEquals("https://example.com/%D1%8F?q=%D0%B1#%D1%8F", url);
        assertEquals(url, DesktopWebShortcut.normalizeUrl(url));
        final var entry = (DesktopWebShortcut) DesktopEntryFile.parse(
                DesktopEntryFile.encodeWebLink("Example", url));
        assertEquals(url, entry.url);
    }

    @Test
    public void asciiUrlRespectsExactLimit() {
        final String prefix = "https://example.com/";
        final String url = prefix + "a".repeat(8192 - prefix.length());
        assertEquals(url, DesktopWebShortcut.normalizeUrl(url));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopWebShortcut.normalizeUrl(url + "a"));
    }

    @Test
    public void unicodeExpansionCannotExceedPersistedUrlLimit() {
        final String prefix = "https://example.com/";
        final int pathBytes = 8192 - prefix.length();
        final String url = prefix + "\u044f".repeat(pathBytes / 6)
                + "a".repeat(pathBytes % 6);
        final String normalized = DesktopWebShortcut.normalizeUrl(url);
        assertEquals(8192, normalized.length());
        assertEquals(normalized, DesktopWebShortcut.normalizeUrl(normalized));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopWebShortcut.normalizeUrl(url + "a"));
    }
}
