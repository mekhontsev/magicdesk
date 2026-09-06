package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopAutomationTerminalWindowsTest {
    @Test
    public void readTailKeepsTheNewestWholeCharactersWithinTheLimit() {
        final String text = "prefix\ud83d\ude80ok";
        assertEquals("ok", DesktopAutomationTerminalWindows.readTail(text, 3));
        assertEquals("\ud83d\ude80ok", DesktopAutomationTerminalWindows.readTail(text, 4));
        assertEquals(text, DesktopAutomationTerminalWindows.readTail(text, text.length()));
        assertEquals("", DesktopAutomationTerminalWindows.readTail("\ud83d\ude80", 1));
        assertEquals("", DesktopAutomationTerminalWindows.readTail("", 1));
        assertEquals("c", DesktopAutomationTerminalWindows.readTail("abc", 1));
    }
}
