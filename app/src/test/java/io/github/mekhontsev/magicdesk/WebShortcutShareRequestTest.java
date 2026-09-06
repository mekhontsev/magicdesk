package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class WebShortcutShareRequestTest {
    private static final String URL = "https://example.com/path";

    @Test
    public void titleLimitCannotSplitASupplementaryCharacter() {
        final String prefix = "a".repeat(119);
        assertEquals(prefix, WebShortcutShareRequest.normalizeName(prefix + "\ud83d\ude80", URL));
    }

    @Test
    public void titleAtTheExactLimitRetainsTheWholeCharacter() {
        final String title = "a".repeat(118) + "\ud83d\ude80";
        assertEquals(title, WebShortcutShareRequest.normalizeName(title, URL));
    }

    @Test
    public void titleStillCollapsesWhitespaceAndFallsBackToHost() {
        assertEquals("Example title", WebShortcutShareRequest.normalizeName("\t Example \n title  ", URL));
        assertEquals("example.com", WebShortcutShareRequest.normalizeName(" \n ", URL));
        assertEquals("example.com", WebShortcutShareRequest.normalizeName(null, URL));
    }

    @Test
    public void oversizedUrlInputIsRejectedBeforeCopyingOrScanning() {
        assertNull(WebShortcutShareRequest.findHttpUrl(new UncopiedText()));
    }

    @Test
    public void rawShareTextLimitIncludesSurroundingWhitespace() {
        assertNull(WebShortcutShareRequest.findHttpUrl(" ".repeat(32 * 1024) + URL));
        assertEquals(URL, WebShortcutShareRequest.findHttpUrl(" \t" + URL + "\n"));
    }

    @Test
    public void titleOnlyCopiesTheBoundedPrefix() {
        assertEquals("Title", WebShortcutShareRequest.normalizeName(new UncopiedText(), URL));
    }

    private static final class UncopiedText implements CharSequence {
        @Override
        public int length() {
            return Integer.MAX_VALUE;
        }

        @Override
        public char charAt(final int index) {
            return index < 5 ? "Title".charAt(index) : ' ';
        }

        @Override
        public CharSequence subSequence(final int start, final int end) {
            if (start != 0 || end > 32 * 1024) {
                throw new AssertionError("only the bounded prefix may be copied");
            }
            return end < 5 ? "Title".substring(0, end) : "Title" + " ".repeat(end - 5);
        }

        @Override
        public String toString() {
            throw new AssertionError("oversized shared text must not be copied in full");
        }
    }
}
