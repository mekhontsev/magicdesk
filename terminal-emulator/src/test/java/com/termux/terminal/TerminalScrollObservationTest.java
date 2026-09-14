package com.termux.terminal;

import java.util.ArrayList;
import java.util.List;

public final class TerminalScrollObservationTest extends TerminalTestCase {
    private final List<String> events = new ArrayList<>();
    private int resets;
    private final List<String> writes = new ArrayList<>();

    private TerminalEmulator.ScrollListener observe() {
        TerminalEmulator.ScrollListener listener = new TerminalEmulator.ScrollListener() {
            @Override public void onScroll(int left, int top, int right, int bottom, int rows) {
                events.add(left + "," + top + "," + right + "," + bottom + ":" + rows);
            }
            @Override public void onCellsChanged(int l, int t, int r, int b) {
                writes.add(l + "," + t + "," + r + "," + b);
            }
            @Override public void onScreenReset() { resets++; }
            @Override public void onScrollComplete() { }
        };
        mTerminal.setScrollListener(listener);
        return listener;
    }

    public void testInsertAndDeletePublishExactRegionBeforeMutation() {
        withTerminalSized(6, 5).enterString("AAAAAA\r\nBBBBBB\r\nCCCCCC\r\nDDDDDD\r\nSTATUS");
        List<String> departing = new ArrayList<>();
        mTerminal.setScrollListener(new TerminalEmulator.ScrollListener() {
            @Override public void onScroll(int left, int top, int right, int bottom, int rows) {
                int row = rows > 0 ? bottom - rows : top;
                departing.add(mTerminal.getSelectedText(left, row, right - 1, row));
            }
            @Override public void onCellsChanged(int l, int t, int r, int b) { }
            @Override public void onScreenReset() { }
            @Override public void onScrollComplete() { }
        });
        enterString("\033[1;4r\033[H\033[1L");
        assertLinesAre("      ", "AAAAAA", "BBBBBB", "CCCCCC", "STATUS");
        enterString("NEWNEW\r\033[1M");
        assertEquals(List.of("DDDDDD", "NEWNEW"), departing);
        assertLinesAre("AAAAAA", "BBBBBB", "CCCCCC", "      ", "STATUS");
    }

    public void testFragmentedTmuxWheelKeepsFiveEditsWithoutMarginReset() {
        withTerminalSized(41, 23);
        observe();
        String bytes = "\033[1;22r\033[1;1H" + "\033[1Lnew\r".repeat(5) + "\033[1;23r";
        for (int i = 0; i < bytes.length(); i++) enterString(bytes.substring(i, i + 1));
        assertEquals(java.util.Collections.nCopies(5, "0,0,41,22:1"), events);
        assertEquals(0, resets);
    }

    public void testStackedPanesAndScrollCommands() {
        withTerminalSized(80, 24);
        observe();
        enterString("\033[13;23r\033[13;1H\033[2M\033[2L\033[3S\033[2T\033M");
        assertEquals(List.of("0,12,80,23:-2", "0,12,80,23:2", "0,12,80,23:-3",
                "0,12,80,23:2", "0,12,80,23:1"), events);
    }

    public void testHorizontalScrollMarginsArePublishedForSuAndSd() {
        withTerminalSized(20, 10);
        observe();
        enterString("\033[?69h\033[3;12s\033[2;8r\033[2S\033[3T");
        assertEquals(List.of("2,1,12,8:-2", "2,1,12,8:3"), events);
    }

    public void testCursorOutsideRegionDoesNotEditOrAnimate() {
        withTerminalSized(6, 5).enterString("AAAAAA\r\nBBBBBB\r\nCCCCCC\r\nDDDDDD\r\nSTATUS");
        observe();
        enterString("\033[2;4r\033[H\033[L\033[M\033[5;1H\033[L\033[M");
        assertTrue(events.isEmpty());
        assertLinesAre("AAAAAA", "BBBBBB", "CCCCCC", "DDDDDD", "STATUS");
    }

    public void testRepaintDoesNotInventScrollingAndLifecycleResetsPresentation() {
        withTerminalSized(8, 5);
        TerminalEmulator.ScrollListener listener = observe();
        enterString("\033[HREPAINT\033[3;2HUPDATE");
        assertTrue(events.isEmpty());
        enterString("\033[2J");
        assertEquals(1, resets);
        enterString("\033[?1049h");
        assertTrue(resets >= 2);
        resets = 0;
        resize(10, 6);
        assertTrue(resets > 0);
        resets = 0;
        mTerminal.reset();
        assertTrue(resets > 0);
        mTerminal.removeScrollListener(listener);
        int before = resets;
        enterString("\033[L\033[2J");
        assertTrue(events.isEmpty());
        assertEquals(before, resets);
    }

    public void testOrdinaryStreamingOutputDoesNotQueueAnimation() {
        withTerminalSized(8, 5);
        observe();
        enterString("line\r\n".repeat(100));
        assertTrue(events.isEmpty());
        assertTrue(resets > 0);
    }

    public void testScrollTransportIsNotReportedAsRepainting() {
        withTerminalSized(8, 6);
        TerminalEmulator.ScrollListener listener = observe();
        enterString("\033[2;5r\033[2;1H\033[L\033[M\033[S\033[T\033M");
        assertEquals(5, events.size());
        assertTrue(writes.isEmpty());
        enterString("\033[2;3Hxy");
        assertEquals(List.of("2,1,3,2", "3,1,4,2"), writes);
        writes.clear();
        enterString("\033[?69h\033[3;6s\033[S\033[T\033M");
        assertTrue(writes.isEmpty());
        mTerminal.removeScrollListener(listener);
        enterString("\033[2;3Hz\033[L");
        assertTrue(writes.isEmpty());
    }

    public void testRepaintCoversWideGlyphsCopiesErasesAndStyles() {
        withTerminalSized(8, 6);
        observe();
        enterString("\033[L\033[2;2H\u754c");
        assertEquals(List.of("1,1,3,2"), writes);
        writes.clear();
        enterString("\033[2;3Hx");
        assertEquals(List.of("1,1,3,2"), writes);
        writes.clear();
        mTerminal.getScreen().blockCopy(0, 2, 3, 1, 2, 3);
        assertEquals(List.of("2,3,5,4"), writes);
        writes.clear();
        enterString("\033[3;1H\033[2X");
        assertEquals(List.of("0,2,1,3", "1,2,2,3"), writes);
        writes.clear();
        mTerminal.getScreen().setOrClearEffect(TextStyle.CHARACTER_ATTRIBUTE_BOLD,
                true, false, true, 0, 8, 2, 1, 3, 4);
        assertEquals(List.of("1,2,4,3"), writes);
    }

    public void testScreenSwitchAndResizeDropCellObservationUntilNextScroll() {
        withTerminalSized(8, 6);
        observe();
        enterString("\033[Lx");
        assertFalse(writes.isEmpty());
        writes.clear();
        enterString("\033[?1049hX\033[?1049lY");
        assertTrue(writes.isEmpty());
        enterString("\033[Lz");
        assertFalse(writes.isEmpty());
        writes.clear();
        resize(10, 8);
        enterString("\033[Hxyz");
        assertTrue(writes.isEmpty());
    }

    public void testPresentationCopiesDoNotStealLiveMarkersOrMetadata() {
        withTerminalSized(8, 6).enterString("\033]8;;https://example.org\033\\abc\033]8;;\033\\");
        TerminalBuffer screen = mTerminal.getScreen();
        TerminalMarker marker = screen.mark(1, 0);
        TerminalRow live = screen.mLines[screen.externalToInternalRow(0)];
        TerminalRow copy = live.snapshot();
        assertTrue(copy.sameCell(live, 1));
        assertEquals(live.getHyperlink(1), copy.getHyperlink(1));
        copy.setChar(1, 'x', TextStyle.NORMAL);
        assertFalse(copy.sameCell(live, 1));
        copy.copyCells(live, 0, 3);
        assertTrue(copy.sameCell(live, 1));
        assertEquals(0, marker.position().row());
        assertEquals(1, marker.position().column());
        assertLineIs(0, "abc     ");
    }

    public void testWriteNotificationsSeeCommittedCells() {
        withTerminalSized(8, 6);
        List<String> observed = new ArrayList<>();
        mTerminal.setScrollListener(new TerminalEmulator.ScrollListener() {
            @Override public void onScroll(int l, int t, int r, int b, int rows) { }
            @Override public void onScrollComplete() {
                observed.add("blank=" + mTerminal.getSelectedText(0, 0, 7, 0));
            }
            @Override public void onCellsChanged(int l, int t, int r, int b) {
                observed.add(mTerminal.getSelectedText(l, t, r - 1, t));
            }
            @Override public void onScreenReset() { }
        });
        enterString("\033[Labc");
        assertEquals(List.of("blank=", "a", "b", "c"), observed);
    }
}
