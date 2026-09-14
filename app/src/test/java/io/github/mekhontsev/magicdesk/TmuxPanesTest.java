package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public final class TmuxPanesTest {
    @Test public void listHasStableTargetsAndActiveFlags() throws Exception {
        final var panes = TmuxPanes.parseList("MAGICDESK_PANES\n$1 %3 @2 1 0 111 222 /dev/pts/4\n$1 %4 @2 1 1 112 223 /dev/pts/5\n");
        assertEquals(2, panes.size());
        assertFalse(panes.get(0).activePane());
        assertTrue(panes.get(1).activeWindow());
        final var target = panes.get(1).target();
        assertEquals(target, TmuxPanes.Target.parse(target.token()));
        assertEquals(223, target.endpoint().startTicks());
        assertTrue(TmuxPanes.parseList("MAGICDESK_PANES\n").isEmpty());
    }

    @Test public void malformedTargetsAndResponsesFail() {
        assertThrows(IOException.class, () -> TmuxPanes.parseList("tmux not found"));
        assertThrows(IOException.class, () -> TmuxPanes.parseList("MAGICDESK_PANES\n$1 %2 @3 1 1\n"));
        assertThrows(IllegalArgumentException.class, () -> TmuxPanes.Target.parse("$1:$(exit):1 2 /dev/pts/3"));
        assertThrows(IllegalArgumentException.class, () -> TmuxPanes.listCommand("$(exit)"));
    }

    @Test public void emissionPinsPaneAndNeverFallsBackToActivePane() throws Exception {
        final var target = TmuxPanes.Target.parse("$1:%2:3 4 /dev/pts/5");
        final String command = TmuxPanes.emitCommand(target, new byte[]{65});
        assertTrue(command.contains("list-panes -s -t '$1'"));
        assertTrue(command.contains("'%2 3 /dev/pts/5'"));
        assertTrue(command.contains("--emit-pty 3 4 '/dev/pts/5' 1"));
        assertFalse(command.contains("send-keys"));
        assertFalse(command.contains("pipe-pane"));
        assertTrue(command.contains("MAGICDESK_EMIT 0 116"));
    }
}
