package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopAutomationStateReaderTest {
    @Test
    public void cursorBeyondEndCannotOverflowIntoANegativeNextCursor() {
        assertEquals(5, DesktopAutomationStateReader.pageEnd(5, Integer.MAX_VALUE, 200));
        assertEquals(0, DesktopAutomationStateReader.pageEnd(0, Integer.MAX_VALUE, 200));
        assertEquals(5, DesktopAutomationStateReader.pageEnd(5, 4, 2));
        assertEquals(4, DesktopAutomationStateReader.pageEnd(5, 2, 2));
    }
}
