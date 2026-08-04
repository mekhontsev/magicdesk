package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class InputMethodMenuControllerTest {
    @Test
    public void shellQuoteProtectsSingleQuotes() {
        assertEquals("'plain'", InputMethodMenuController.shellQuote("plain"));
        assertEquals("'a'\\''b'", InputMethodMenuController.shellQuote("a'b"));
    }
}
