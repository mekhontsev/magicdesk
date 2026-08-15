package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ConsoleCommandHistoryTest {
    @Test
    public void navigatesBackAndRestoresDraft() {
        final ConsoleCommandHistory history = new ConsoleCommandHistory();
        history.record("id");
        history.record("wm size");

        assertEquals("wm size", history.previous("draft"));
        assertEquals("id", history.previous("wm size"));
        assertEquals("wm size", history.next());
        assertEquals("draft", history.next());
        assertNull(history.next());
    }

    @Test
    public void ignoresBlankAndConsecutiveDuplicateCommands() {
        final ConsoleCommandHistory history = new ConsoleCommandHistory();
        history.record("  ");
        assertNull(history.previous(""));

        history.record("id");
        history.record("id");
        assertEquals("id", history.previous(""));
        assertEquals("id", history.previous(""));
    }
}
