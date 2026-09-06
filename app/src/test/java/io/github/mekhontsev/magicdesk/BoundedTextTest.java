package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class BoundedTextTest {
    @Test
    public void exactAndShortTextNeedNoCopy() {
        final String text = " \tText\n";
        assertSame(text, BoundedText.prefix(text, text.length()));
        assertSame(text, BoundedText.prefix(text, text.length() + 1));
    }

    @Test
    public void emptyInputAndZeroBudgetProduceEmptyText() {
        assertEquals("", BoundedText.prefix(null, 8));
        assertEquals("", BoundedText.prefix("", 8));
        assertEquals("", BoundedText.prefix("text", 0));
        assertThrows(IllegalArgumentException.class, () -> BoundedText.prefix("text", -1));
    }

    @Test
    public void utf16BudgetDoesNotBecomeACodePointCount() {
        assertEquals("\ud83d\ude80", BoundedText.prefix("\ud83d\ude80\ud83d\ude80", 3));
        assertEquals("", BoundedText.prefix("\ud83d\ude80", 1));
    }

    @Test
    public void everyBoundaryPreservesAValidUtf8RoundTrip() {
        final String text = "A\ud83d\ude80\u4e16\u754c\ud834\udd1eZ";
        for (int limit = 0; limit <= text.length() + 1; limit++) {
            final String result = BoundedText.prefix(new StringBuilder(text), limit);
            assertTrue(result.length() <= limit);
            assertTrue(text.startsWith(result));
            assertEquals(result, new String(result.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    public void truncationDoesNotTrimWhitespace() {
        assertEquals(" \t ", BoundedText.prefix(" \t \ntext", 3));
    }
}
