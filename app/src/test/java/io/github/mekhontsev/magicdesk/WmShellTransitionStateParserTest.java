package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WmShellTransitionStateParserTest {
    @Test
    public void parsesIdleTracks() {
        assertEquals(
                WmShellTransitionStateParser.State.IDLE,
                WmShellTransitionStateParser.parse(dump(
                        "none", "none", "active=null", "active=null")));
    }

    @Test
    public void parsesActiveTrack() {
        assertEquals(
                WmShellTransitionStateParser.State.BUSY,
                WmShellTransitionStateParser.parse(dump(
                        "none", "none", "active=TransitionInfo{42}")));
    }

    @Test
    public void parsesPendingTransition() {
        assertEquals(
                WmShellTransitionStateParser.State.BUSY,
                WmShellTransitionStateParser.parse(dump(
                        "TransitionRecord{42}",
                        "none",
                        "active=null")));
    }

    @Test
    public void rejectsMissingShellSection() {
        assertEquals(
                WmShellTransitionStateParser.State.UNAVAILABLE,
                WmShellTransitionStateParser.parse("SystemUI state"));
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
