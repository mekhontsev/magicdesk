package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WmShellTransitionStateParserTest {
    @Test
    public void parsesIdleTracks() {
        assertEquals(
                WmShellTransitionStateParser.State.IDLE,
                WmShellTransitionStateParser.parse(dump(
                        "none", "none", "active=null", "active=null")).state);
    }

    @Test
    public void parsesActiveTrack() {
        assertEquals(
                WmShellTransitionStateParser.State.BUSY,
                WmShellTransitionStateParser.parse(dump(
                        "none", "none", "active=TransitionInfo{42}")).state);
    }

    @Test
    public void parsesPendingTransition() {
        assertEquals(
                WmShellTransitionStateParser.State.BUSY,
                WmShellTransitionStateParser.parse(dump(
                        "TransitionRecord{42}",
                        "none",
                        "active=null")).state);
    }

    @Test
    public void rejectsMissingShellSection() {
        assertEquals(
                WmShellTransitionStateParser.State.UNAVAILABLE,
                WmShellTransitionStateParser.parse("SystemUI state").state);
    }

    @Test
    public void retainsUnreadyPendingTokenEvenWhenEveryTrackIsIdle() {
        final WmShellTransitionStateParser.Snapshot snapshot =
                WmShellTransitionStateParser.parse(dump(
                        "token=android.os.BinderProxy@4de864c\n"
                                + "      id=-1\n      handler=null",
                        "none", "active=null", "active=null"));
        assertEquals(WmShellTransitionStateParser.State.BUSY, snapshot.state);
        assertTrue(snapshot.detail.contains("pending=[token=android.os.BinderProxy@4de864c; id=-1; handler=null]"));
        assertTrue(snapshot.detail.contains("ready=[none]"));
        assertTrue(snapshot.detail.contains("Track #1; active=null"));
    }

    @Test
    public void reportsReadyDuringSyncAsBusy() {
        final WmShellTransitionStateParser.Snapshot snapshot =
                WmShellTransitionStateParser.parse(dump(
                        "none", "token=ready\n      id=42", "active=null"));
        assertEquals(WmShellTransitionStateParser.State.BUSY, snapshot.state);
        assertTrue(snapshot.detail.contains("ready=[token=ready; id=42]"));
    }

    @Test
    public void doesNotInterpretMissingQueueContentsAsIdle() {
        assertEquals(WmShellTransitionStateParser.State.UNAVAILABLE,
                WmShellTransitionStateParser.parse(dump(
                        "", "none", "active=null")).state);
        assertEquals(WmShellTransitionStateParser.State.UNAVAILABLE,
                WmShellTransitionStateParser.parse(dump(
                        "none", "", "active=null")).state);
        assertEquals(WmShellTransitionStateParser.State.UNAVAILABLE,
                WmShellTransitionStateParser.parse(dump("none", "none")).state);
    }

    @Test
    public void capsDetailsWithoutStoppingStateInspection() {
        final WmShellTransitionStateParser.Snapshot snapshot =
                WmShellTransitionStateParser.parse(dump(
                        "none", "none", "active=null\n".repeat(200),
                        "active=TransitionInfo{42}"));
        assertEquals(WmShellTransitionStateParser.State.BUSY, snapshot.state);
        assertTrue(snapshot.detail.length() < 2500);
        assertTrue(snapshot.detail.contains("..."));
    }

    @Test
    public void ignoresOtherDumpSectionsAndSupportsCrLf() {
        final WmShellTransitionStateParser.Snapshot snapshot =
                WmShellTransitionStateParser.parse((dump(
                        "none", "none", "active=null") + "active=unrelated\n")
                        .replace("\n", "\r\n"));
        assertEquals(WmShellTransitionStateParser.State.IDLE, snapshot.state);
        assertTrue(snapshot.toString().startsWith("IDLE; pending=[none]"));
    }

    @Test
    public void emptyDumpRemainsUnavailable() {
        assertEquals(WmShellTransitionStateParser.State.UNAVAILABLE,
                WmShellTransitionStateParser.parse(null).state);
        assertEquals(WmShellTransitionStateParser.State.UNAVAILABLE,
                WmShellTransitionStateParser.parse("").state);
    }

    @Test
    public void acceptsShellDumpEndingAtSurfaceRegistry() {
        final String output = dump("none", "none", "active=null", "active=null")
                .replace("AppResourceProvider", "SurfaceControlRegistry")
                + "      active=unrelated\n    NORMAL dump took 7ms -- WMShell\n";
        assertEquals(WmShellTransitionStateParser.State.IDLE,
                WmShellTransitionStateParser.parse(output).state);
    }

    private static String dump(
            final String pending,
            final String ready,
            final String... tracks) {
        final StringBuilder output = new StringBuilder()
                .append("SystemUI\n")
                .append("    ShellTransitions\n")
                .append("    Pending Transitions:\n")
                .append("      ").append(pending).append('\n')
                .append("    Ready-during-sync Transitions:\n")
                .append("      ").append(ready).append('\n')
                .append("    Tracks:\n");
        for (int index = 0; index < tracks.length; index++) {
            output.append("      Track #").append(index).append('\n')
                    .append("      ").append(tracks[index]).append('\n');
        }
        return output.append("\n    AppResourceProvider\n").toString();
    }
}
