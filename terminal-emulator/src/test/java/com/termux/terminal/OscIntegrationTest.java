package com.termux.terminal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class OscIntegrationTest extends TerminalTestCase {
    private static String osc(String text) { return "\033]" + text + "\007"; }
    private TerminalHyperlink link(int x, int y) { return mTerminal.getScreen().getHyperlink(x, y); }

    public void testHyperlinksFollowPaintedCellsNotSubsequentText() {
        withTerminalSized(10, 4).enterString(osc("8;id=a;https://example.org/a;b") + "AB" + osc("8;;") + "C");
        assertEquals("https://example.org/a;b", link(0, 0).uri());
        assertEquals("a", link(1, 0).id());
        assertNull(link(2, 0));
        enterString("\rX");
        assertNull(link(0, 0));
        assertNotNull(link(1, 0));
    }

    public void testHyperlinksSurviveUtf8ChunksWideCharactersAndReflow() {
        withTerminalSized(6, 4);
        byte[] bytes = (osc("8;;https://example.org") + "A\u754cB\u0301CD" + osc("8;;")).getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) mTerminal.append(new byte[]{value}, 1);
        assertNotNull(link(1, 0));
        assertEquals(link(1, 0), link(2, 0));
        resize(4, 4);
        assertEquals("https://example.org", link(1, 0).uri());
        assertNotNull(link(0, 1));
    }

    public void testWideCharacterOverwriteClearsOnlyReplacedLinkCells() {
        withTerminalSized(8, 3).enterString(osc("8;;https://one") + "\u754c" + osc("8;;https://two") + "\033[1;2HX");
        assertNull(link(0, 0));
        assertEquals("https://two", link(1, 0).uri());
        enterString("\033[1;1H" + osc("8;;https://three") + "\u754c" + osc("8;;") + "\033[1;1HZ");
        assertNull(link(0, 0));
        assertNull(link(1, 0));
    }

    public void testHyperlinkInsertDeleteEraseAndReset() {
        withTerminalSized(8, 3).enterString(osc("8;;https://one") + "ABC" + osc("8;;") + "\r\033[@");
        assertNull(link(0, 0));
        assertNotNull(link(1, 0));
        assertNotNull(link(3, 0));
        enterString("\033[P");
        assertNotNull(link(0, 0));
        assertNotNull(link(2, 0));
        enterString("\033[K");
        assertNull(link(1, 0));
        enterString(osc("8;;https://one") + "\033cX");
        assertNull(link(0, 0));
    }

    public void testMalformedHyperlinkClosesPreviousTarget() {
        withTerminalSized(8, 3).enterString(osc("8;;https://one") + "A" + osc("8;missing") + "B");
        assertNull(link(1, 0));
        enterString(osc("8;;https://host/\npath") + "C");
        assertNull(link(2, 0));
    }

    public void testCommandTextOutputAndExitCodeAreReportedByMarks() {
        withTerminalSized(24, 5);
        command("echo hello", "hello", 7);
        var result = mTerminal.getCommandHistory().snapshots().get(0);
        assertEquals("echo hello", result.command());
        assertTrue(result.commandKnown());
        assertEquals("completed", result.state());
        assertEquals(Integer.valueOf(7), result.exitCode());
        assertEquals("hello", mTerminal.getCommandHistory().output(result.id()));
    }

    public void testCommandMarkersSurviveScrollAndWidthResize() {
        withTerminalSized(24, 4);
        command("echo abcdefgh", "abcdefgh", 0);
        enterString("\r\n\r\n\r\n");
        resize(5, 4);
        var result = mTerminal.getCommandHistory().snapshots().get(0);
        assertEquals("abcdefgh", mTerminal.getCommandHistory().output(result.id()));
        assertNotNull(result.position());
        resize(30, 6);
        assertEquals("abcdefgh", mTerminal.getCommandHistory().output(result.id()));
    }

    public void testErasedOrExpiredOutputIsUnavailable() {
        withTerminalSized(16, 4);
        command("echo hello", "hello", 0);
        enterString("\033[2J");
        assertNull(mTerminal.getCommandHistory().output(1));
        mTerminal.getCommandHistory().clear();
        assertTrue(mTerminal.getCommandHistory().snapshots().isEmpty());
    }

    public void testMissingMarksDoNotInventExecutionOrSuccess() {
        withTerminalSized(16, 4).enterString(osc("133;D;0"));
        assertEquals("unknown", mTerminal.getCommandHistory().state());
        enterString(osc("133;A") + "$ " + osc("133;B") + "abc" + osc("133;A"));
        var previous = mTerminal.getCommandHistory().snapshots().get(0);
        assertEquals("incomplete", previous.state());
        assertFalse(previous.commandKnown());
        assertNull(previous.exitCode());
        assertFalse(previous.outputAvailable());
    }

    public void testAbortAndUnknownExitAreDistinct() {
        withTerminalSized(16, 4).enterString(osc("133;A") + "$ " + osc("133;B") + osc("133;D;0"));
        assertEquals("cancelled", mTerminal.getCommandHistory().state());
        enterString(osc("133;A") + "$ " + osc("133;B") + "true\r\n" + osc("133;C") + osc("133;D"));
        var completed = mTerminal.getCommandHistory().snapshots().get(1);
        assertEquals("completed", completed.state());
        assertNull(completed.exitCode());
        assertEquals("", mTerminal.getCommandHistory().output(completed.id()));
    }

    public void testAlternateScreenDoesNotCreateOuterShellCommands() {
        withTerminalSized(16, 4).enterString(osc("133;A") + "$ " + osc("133;B") + "vi\r\n" + osc("133;C"));
        enterString("\033[?1049h" + osc("133;A") + "alternate" + "\033[?1049l" + osc("133;D;0"));
        assertEquals(1, mTerminal.getCommandHistory().snapshots().size());
        assertEquals("completed", mTerminal.getCommandHistory().state());
    }

    public void testHistoryIsBoundedAndResetClearsIt() {
        withTerminalSized(16, 4);
        for (int i = 0; i < 200; i++) command("true", "", 0);
        assertEquals(128, mTerminal.getCommandHistory().snapshots().size());
        enterString("\033c");
        assertEquals("unknown", mTerminal.getCommandHistory().state());
    }

    public void testNotificationProgressAndTitleEvents() {
        final List<String> notifications = new ArrayList<>();
        final List<String> progress = new ArrayList<>();
        mOutput = new MockTerminalOutput() {
            @Override public void onNotification(String message) { notifications.add(message); }
            @Override public void onProgressChanged(int state, int percent) { progress.add(state + ":" + percent); }
        };
        withTerminalSized(16, 4).enterString(osc("0;Build") + "\033]2;Ready\033\\"
                + osc("9;Finished") + osc("9;4;1;50") + osc("9;4;3") + osc("9;4")
                + osc("9;4;1;101") + osc("9;4;bogus") + osc("9;12;ignored"));
        assertEquals(List.of("Finished"), notifications);
        assertEquals(List.of("0:-1", "1:50", "3:-1", "0:-1"), progress);
        assertEquals("Ready", mTerminal.getTitle());
        enterString(osc("9;4;1;70") + "\033c");
        assertEquals("0:-1", progress.get(progress.size() - 1));
    }

    public void testLinkOnTrailingSpacesSurvivesReflowBeforeCursor() {
        withTerminalSized(12, 4).enterString("abc" + osc("8;;https://example.com") + "    " + osc("8;;") + "\r");
        mTerminal.resize(8, 4, 0, 0);
        assertEquals("https://example.com", mTerminal.getScreen().getHyperlink(6, 0).uri());
        assertNull(mTerminal.getScreen().getHyperlink(7, 0));
    }

    private void command(String command, String output, int status) {
        enterString(osc("133;A") + "$ " + osc("133;B") + command + "\r\n"
                + osc("133;C") + output + (output.isEmpty() ? "" : "\r\n") + osc("133;D;" + status));
    }
}
