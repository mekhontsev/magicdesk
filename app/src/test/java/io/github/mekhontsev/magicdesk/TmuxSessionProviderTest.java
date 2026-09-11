package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TmuxSessionProviderTest {
    @Test
    public void unavailableTmuxIsAValidEmptySnapshot() {
        final TmuxSessionProvider.Snapshot snapshot =
                TmuxSessionProvider.parse(
                        "__MAGICDESK_TMUX_UNAVAILABLE__\n");

        assertFalse(snapshot.available);
        assertTrue(snapshot.sessions.isEmpty());
        assertEquals("tmux is not installed in Termux", snapshot.detail);
    }

    @Test
    public void parsesAvailableSessionsAndAttachmentState() {
        final TmuxSessionProvider.Snapshot snapshot =
                TmuxSessionProvider.parse(
                        "__MAGICDESK_TMUX_AVAILABLE__\n"
                                + "$0\twork\t2\t1\t1234\n"
                                + "$3\tserver logs\t1\t0\t5678\n"
                                + "CLIENT\t42\t$0\n");

        assertTrue(snapshot.available);
        assertEquals(2, snapshot.sessions.size());
        assertEquals("work", snapshot.sessions.get(0).name);
        assertEquals(2, snapshot.sessions.get(0).windows);
        assertTrue(snapshot.sessions.get(0).attached());
        assertEquals("$3", snapshot.sessions.get(1).id);
        assertFalse(snapshot.sessions.get(1).attached());
        assertEquals(5678L, snapshot.sessions.get(1).createdSeconds);
        assertEquals("$0", snapshot.clients.get(42L));
    }

    @Test
    public void availableTmuxMayHaveNoServerOrSessions() {
        final TmuxSessionProvider.Snapshot snapshot =
                TmuxSessionProvider.parse(
                        "__MAGICDESK_TMUX_AVAILABLE__\n");

        assertTrue(snapshot.available);
        assertTrue(snapshot.sessions.isEmpty());
    }

    @Test
    public void rejectsMalformedSessionRecords() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.parse(
                        "__MAGICDESK_TMUX_AVAILABLE__\n"
                                + "work\twork\t1\t0\t1\n"));
    }

    @Test
    public void commandsQuoteValidatedIdentifiersAndNames() {
        assertEquals(
                "exec tmux attach-session -t '$12'",
                TmuxSessionProvider.attachCommand("$12"));
        assertEquals(
                "tmux new-session -d -s 'team'\"'\"'s work'",
                TmuxSessionProvider.createCommand("team's work"));
    }

    @Test
    public void rejectsTmuxSeparatorsAndInvalidIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.createCommand("work.dev"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.createCommand("work:1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.attachCommand("work"));
    }

    @Test
    public void sessionActionsCheckCreationIdentityBeforeUsingRecycledId() {
        final var session = new TmuxSessionProvider.Session("$12", "work", 1, 0, 1234);
        assertEquals("test \"$(tmux display-message -p -t '$12' '#{session_created}' 2>/dev/null)\" = '1234'"
                        + " || { printf 'tmux session no longer exists\\n' >&2; exit 1; }\n"
                        + "exec tmux attach-session -t '$12'",
                TmuxSessionProvider.sessionCommand(session, TmuxSessionProvider.attachCommand(session.id)));
    }
}
