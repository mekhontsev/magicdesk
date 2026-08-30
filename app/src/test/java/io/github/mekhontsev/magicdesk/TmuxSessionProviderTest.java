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
                                + "$3\tserver logs\t1\t0\t5678\n");

        assertTrue(snapshot.available);
        assertEquals(2, snapshot.sessions.size());
        assertEquals("work", snapshot.sessions.get(0).name);
        assertEquals(2, snapshot.sessions.get(0).windows);
        assertTrue(snapshot.sessions.get(0).attached());
        assertEquals("$3", snapshot.sessions.get(1).id);
        assertFalse(snapshot.sessions.get(1).attached());
        assertEquals(5678L, snapshot.sessions.get(1).createdSeconds);
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
                "exec tmux new-session -A -s 'team'\"'\"'s work'",
                TmuxSessionProvider.openOrCreateCommand("team's work"));
    }

    @Test
    public void rejectsTmuxSeparatorsAndInvalidIdentifiers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.openOrCreateCommand("work.dev"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.openOrCreateCommand("work:1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TmuxSessionProvider.attachCommand("work"));
    }
}
