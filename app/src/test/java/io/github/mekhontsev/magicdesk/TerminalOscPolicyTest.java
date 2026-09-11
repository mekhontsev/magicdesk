package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TerminalOscPolicyTest {
    @Test public void onlyExplicitSafeDestinationsCanOpen() {
        assertTrue(TerminalLink.parse("https://example.com/a;b?q=c").canOpen());
        assertEquals("/sdcard/a b.txt", TerminalLink.parse("file:///sdcard/a%20b.txt").localPath());
        assertTrue(TerminalLink.parse("file://localhost/sdcard/a").canOpen());
        for (String value : new String[]{"file://other-host/etc/passwd", "file://localhost:80/a",
                "file://user@localhost/a", "file:///a%00", "file:relative", "https://name@host/a",
                "intent://x", "javascript:alert(1)", "content://secrets/a", "not a uri"}) {
            assertFalse(value, TerminalLink.parse(value).canOpen());
        }
    }

    @Test public void notificationFloodsHaveNoDelayedQueueAndSessionsAreIndependent() {
        final var first = new TerminalNotificationLimiter();
        final var second = new TerminalNotificationLimiter();
        assertTrue(first.accept(0));
        for (int now = 1; now < 2000; now++) { assertFalse(first.accept(now)); }
        assertTrue(second.accept(100));
        assertTrue(first.accept(2000));
        assertFalse(first.accept(2001));
        assertTrue(first.accept(10000));
    }

    @Test public void titleCannotCarryBidiControlCharacters() {
        assertEquals("Console - abcdef", TerminalTaskLabel.resolve("Console", null, "abc\u202Edef\u202C"));
    }
}
