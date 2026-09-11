package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class TerminalSessionsTest {
    private static ConsoleTerminalRegistry.Snapshot terminal(String id, String tmux, long pid, boolean ready, int task) {
        return new ConsoleTerminalRegistry.Snapshot(id, task, task < 0 ? -1 : 0, false, ready,
                pid, 80, 24, "/home", "program", "termux", TerminalProcessInfo.unknown(), "", tmux, 100);
    }

    private static TmuxSessionProvider.Snapshot sessions(String clients) {
        return TmuxSessionProvider.parse("__MAGICDESK_TMUX_AVAILABLE__\n"
                + "$0\twork\t2\t1\t100\n$1\tlogs\t1\t0\t200\n" + clients);
    }

    @Test public void tmuxAndItsClientAreOneItemButOrdinaryShellsRemainIndependent() {
        final var client = terminal("terminal-a", "$0", 42, true, 9);
        final var ordinary = terminal("terminal-b", "", 43, true, -1);
        final var items = TerminalSessions.merge(List.of(client, ordinary), sessions("CLIENT\t42\t$0\nCLIENT\t43\t$0\n"));
        assertEquals(3, items.size());
        assertSame(client, items.get(0).terminal());
        assertNull(items.get(1).terminal());
        assertSame(ordinary, items.get(2).terminal());
    }

    @Test public void switchingInsideTmuxUsesCurrentClientMapping() {
        final var client = terminal("terminal-a", "$0", 42, true, 9);
        final var items = TerminalSessions.merge(List.of(client), sessions("CLIENT\t42\t$1\n"));
        assertEquals(2, items.size());
        assertNull(items.get(0).terminal());
        assertSame(client, items.get(1).terminal());
    }

    @Test public void loadingAndUnavailableTmuxDoNotHideRetainedTerminals() {
        final var client = terminal("terminal-a", "$0", 42, true, 9);
        for (final var snapshot : new TmuxSessionProvider.Snapshot[]{null, TmuxSessionProvider.Snapshot.unavailable("missing")}) {
            assertSame(client, TerminalSessions.merge(List.of(client), snapshot).get(0).terminal());
        }
    }

    @Test public void absentClientCannotBeMistakenForNewSessionWithReusedId() {
        final var stale = terminal("terminal-a", "$0", 42, true, 9);
        assertEquals(3, TerminalSessions.merge(List.of(stale), sessions("")).size());
        final var starting = terminal("terminal-b", "$0", -1, false, 10);
        assertEquals(2, TerminalSessions.merge(List.of(starting), sessions("")).size());
        final var reusedId = terminal("terminal-c", "$1", -1, false, 10);
        assertEquals(3, TerminalSessions.merge(List.of(reusedId), sessions("")).size());
    }

    @Test public void multipleOwnedClientsAreNotDuplicateSessionRows() {
        final var first = terminal("terminal-a", "$0", 42, true, -1);
        final var visible = terminal("terminal-b", "$0", 43, true, 9);
        final var items = TerminalSessions.merge(List.of(first, visible), sessions("CLIENT\t42\t$0\nCLIENT\t43\t$0\n"));
        assertEquals(2, items.size());
        assertSame(visible, items.get(0).terminal());
    }

    @Test public void customNameIsNotOverwrittenByOscTitle() {
        final var session = new ConsoleTerminalRegistry.Snapshot("terminal-a", -1, -1, false, true, 42,
                80, 24, "/home", "program OSC", "shell", TerminalProcessInfo.unknown(), "Work", "", 0);
        assertEquals("Work", session.taskLabel("Console"));
    }
}
