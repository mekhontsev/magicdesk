package com.termux.terminal;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Runs the actual cell model and parser without an Android renderer or animation clock. */
public final class TerminalScrollRegionTest {
    private static final class Fixture {
        final TerminalScrollRegion region = new TerminalScrollRegion();
        final TerminalEmulator emulator;

        Fixture() { this(6); }

        Fixture(int rows) {
            emulator = new TerminalEmulator(new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
            }, 8, rows, 10, 10, 100, null);
            append("AAAAAAAA\r\nBBBBBBBB\r\nCCCCCCCC\r\nDDDDDDDD\r\nEEEEEEEE\r\nSTATUS!!");
            emulator.setScrollListener(new TerminalEmulator.ScrollListener() {
                @Override public void onScroll(int l, int t, int r, int b, int rows) {
                    region.reconcileRepaints(emulator);
                    if (region.moving != null && (region.left != l || region.right != r
                            || region.top != t || region.bottom != b || region.direction != Integer.signum(rows)))
                        region.reset();
                    region.begin(emulator, l, t, r, b, rows);
                    region.transport(rows);
                }
                @Override public void onScrollComplete() { region.scrollComplete(emulator); }
                @Override public void onCellsChanged(int l, int t, int r, int b) {
                    region.cellsChanged(emulator, l, t, r, b);
                }
                @Override public void onScreenReset() { region.reset(); }
            });
        }
        void append(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            emulator.append(bytes, bytes.length);
        }
        String moving(int row) {
            TerminalRow line = region.presentationRows(emulator)[row];
            return new String(line.mText, 0, line.getSpaceUsed());
        }
        String live(int row) { return emulator.getSelectedText(0, row, 7, row); }
    }

    @Test public void changingTextTravelsWithItsRowInsteadOfArrivingAheadOfIt() {
        Fixture f = new Fixture();
        f.append("\033[1;5r\033[H\033[Lnew");
        f.append("\033[3;3Hb\033[31mc\033[0m");
        f.region.reconcileRepaints(f.emulator);
        assertEquals("BBbcBBBB", f.moving(2));
        assertTrue(f.region.stationary[2].isEmpty());
        assertEquals("AAAAAAAA", f.moving(1));
        assertEquals("STATUS!!", f.live(5));

        // Animation frames and repeated updates must not change ownership of the text.
        f.append("\033[3;3Hd");
        assertEquals("BBdcBBBB", f.moving(2));
        assertTrue(f.region.stationary[2].isEmpty());
        f.append("\033[H\033[Lnext");
        assertEquals("BBdcBBBB", f.moving(3));
        assertEquals(f.live(3), f.moving(3));
    }

    @Test public void updatedWideGlyphAndCombiningMarkMoveTogether() {
        Fixture f = new Fixture();
        f.append("\033[1;5r\033[H\033[L");
        f.append("\033[3;2H\u754c\u0301");
        f.region.reconcileRepaints(f.emulator);
        assertEquals("B\u754c\u0301BBBBB", f.moving(2));
        assertTrue(f.region.stationary[2].isEmpty());
    }

    @Test public void restoringFooterStillKeepsTheWholeRowStationary() {
        Fixture f = new Fixture();
        f.append("\033[1;6r\033[H\033[M\033[6;1HSTATUS!!");
        f.region.reconcileRepaints(f.emulator);
        assertEquals(6, f.region.stationary.length);
        assertEquals(8, f.region.stationary[5].cardinality());
        assertEquals("STATUS!!", f.live(5));
    }

    @Test public void pairedBadgeRepairStaysFixedWhileUpdatedBodyMoves() {
        Fixture f = new Fixture();
        f.append("\033[1;5H\033[43mBADG\033[0m");
        f.append("\033[1;5r\033[H\033[Lnew \033[43mBADG\033[0m\033[2;5HAAAA");
        f.append("\033[4;2Hx");
        f.region.reconcileRepaints(f.emulator);
        assertEquals("new     ", f.moving(0));
        assertEquals("AAAAAAAA", f.moving(1));
        assertEquals(4, f.region.stationary[0].cardinality());
        assertEquals("CxCCCCCC", f.moving(3));
        assertTrue(f.region.stationary[3].isEmpty());
    }

    @Test public void historyRedrawDoesNotPinAlternatingLettersOrWholeLinesInEitherDirection() {
        for (String edit : new String[]{"L", "2L", "M", "2M"}) {
            Fixture f = new Fixture();
            f.append("\033[HABABABAB\033[2;1HACACACAC\033[3;1HADADADAD");
            f.append("\033[1;5r\033[H\033[" + edit);
            String[] expected = new String[5];
            for (int row = 0; row < 5; row++) expected[row] = String.format("%-8s", f.live(row));
            for (int row = 0; row < 5; row++) {
                String redraw = "\033[" + (row + 1) + ";1H\033[K" + expected[row];
                for (int i = 0; i < redraw.length(); i++) {
                    f.append(redraw.substring(i, i + 1));
                    f.region.presentationRows(f.emulator);
                }
            }
            for (int row = 0; row < 5; row++) {
                assertEquals(edit + " row " + row, expected[row], f.moving(row));
                assertTrue(edit + " row " + row, f.region.stationary[row].isEmpty());
            }
        }
    }

    @Test public void repeatedHistoryLinesAreNotEvidenceOfAStationaryBand() {
        for (String edit : new String[]{"L", "M"}) {
            Fixture f = new Fixture();
            for (int row = 0; row < 5; row++) f.append("\033[" + (row + 1) + ";1HABABABAB");
            f.append("\033[1;5r\033[H\033[" + edit);
            for (int row = 0; row < 5; row++) {
                if ((edit.equals("L") && row == 0) || (edit.equals("M") && row == 4)) continue;
                f.append("\033[" + (row + 1) + ";1HABABABAB");
            }
            f.region.reconcileRepaints(f.emulator);
            for (int row = 0; row < 5; row++) assertTrue(f.region.stationary[row].isEmpty());
        }
    }

    @Test public void twoAdjacentUpdatesAreNotAStationaryPanel() {
        Fixture f = new Fixture();
        f.append("\033[1;5r\033[H\033[Lnew\033[3;1HBbBbBbBb\033[4;1HCcCcCcCc");
        assertEquals("BbBbBbBb", f.moving(2));
        assertEquals("CcCcCcCc", f.moving(3));
        assertTrue(f.region.stationary[2].isEmpty());
        assertTrue(f.region.stationary[3].isEmpty());
    }

    @Test public void sparseCoincidencesDoNotPinIndividualLetters() {
        for (String edit : new String[]{"L", "M"}) {
            Fixture f = new Fixture();
            f.append("\033[1;5r\033[H\033[" + edit);
            for (int column = 1; column <= 7; column += 2) {
                // Both rows update independently; some letters happen to equal their
                // pre-scroll viewport value. This is not a restored overlay.
                f.append("\033[2;" + column + "HB");
                f.region.presentationRows(f.emulator);
                f.append("\033[3;" + column + "HC");
                f.region.presentationRows(f.emulator);
            }
            assertEquals(edit.equals("L") ? "BABABABA" : "BCBCBCBC", f.moving(1));
            assertEquals(edit.equals("L") ? "CBCBCBCB" : "CDCDCDCD", f.moving(2));
            assertTrue(f.region.stationary[1].isEmpty());
            assertTrue(f.region.stationary[2].isEmpty());
        }
    }

    @Test public void styledCounterRestorationIncludesUnchangedLettersInEitherDirection() {
        for (String edit : new String[]{"L", "M"}) {
            for (boolean sourceFirst : new boolean[]{true, false}) {
                Fixture f = new Fixture();
                f.append("\033[3;5H\033[43m1010\033[0m");
                int targetRow = edit.equals("L") ? 4 : 2;
                String source = "\033[3;5H\033[43m1111\033[0m";
                String target = "\033[" + targetRow + ";5HCCCC";
                f.append("\033[1;5r\033[H\033[" + edit);
                f.append(sourceFirst ? source : target);
                f.region.presentationRows(f.emulator);
                f.append(sourceFirst ? target : source);
                f.region.reconcileRepaints(f.emulator);
                assertEquals(4, f.region.stationary[2].cardinality());
                assertEquals("CCCCCCCC", f.moving(targetRow - 1));
                assertTrue(f.region.stationary[targetRow - 1].isEmpty());
            }
        }
    }

    @Test public void identicalRowsWithinARestoredFooterRemainOneStationaryBand() {
        Fixture f = new Fixture();
        f.append("\033[5;1HSTATUS!!");
        f.append("\033[1;6r\033[H\033[M\033[4;1HEEEEEEEE\033[5;1HSTATUS!!\033[6;1HSTATUS!!");
        f.region.reconcileRepaints(f.emulator);
        assertEquals(8, f.region.stationary[4].cardinality());
        assertEquals(8, f.region.stationary[5].cardinality());
        assertEquals("EEEEEEEE", f.moving(3));
        assertTrue(f.region.stationary[3].isEmpty());
    }

    @Test public void repeatedScrollDoesNotCarryFooterUnderlayIntoTheBody() {
        for (int height : new int[]{23, 36}) for (int count : new int[]{1, 5}) {
            Fixture control = new Fixture(height), subject = new Fixture(height);
            int bottom = height - 1, band = bottom - 3;
            for (int row = 0; row < height; row++) {
                String text = "\033[" + (row + 1) + ";1HROW" + row % 10 + "....";
                control.append(text);
                subject.append(text);
            }
            for (int row = band; row < bottom; row++)
                subject.append("\033[" + (row + 1) + ";1H\033[43mFIXED" + row % 10 + "..\033[0m");
            for (int edit = 0; edit < 8; edit++) {
                String scroll = "\033[1;" + bottom + "r\033[H\033[" + count + "M";
                control.append(scroll);
                subject.append(scroll);
                for (int row = bottom - count; row < bottom; row++) {
                    String text = "\033[" + (row + 1) + ";1H\033[KNEW" + edit + "....";
                    control.append(text);
                    subject.append(text);
                }
                for (int row = band - count; row < bottom; row++) {
                    String text = row < band ? control.live(row)
                            : "\033[43mFIXED" + row % 10 + "..\033[0m";
                    subject.append("\033[" + (row + 1) + ";1H" + text);
                }
                for (int row = 0; row < band; row++) {
                    assertEquals("height=" + height + " count=" + count + " edit=" + edit + " row=" + row,
                            control.moving(row), subject.moving(row));
                    assertTrue("body pinned at " + row, subject.region.stationary[row].isEmpty());
                }
            }
        }
    }

    @Test public void semanticScrollTransportsOnlyRegionAndDoesNotMutateParserState() {
        Fixture f = new Fixture();
        f.append("\033[1;5r\033[H\033[Lnew");
        f.region.reconcileRepaints(f.emulator);
        assertTrue(f.moving(0).startsWith("new"));
        assertEquals("AAAAAAAA", f.moving(1));
        assertEquals("STATUS!!", f.live(5));
        assertNull(f.region.moving[5]);
        f.region.moving[1].setChar(0, 'X', TextStyle.NORMAL);
        assertEquals("AAAAAAAA", f.live(1));
    }

    @Test public void fragmentedRepaintAndOneBatchGiveIdenticalLayers() {
        Fixture whole = new Fixture(), pieces = new Fixture();
        String edits = "\033[1;5r\033[H\033[Lnew     \033[2;5Htag\033[3;5H    ";
        whole.append(edits);
        for (int i = 0; i < edits.length(); i++) {
            pieces.append(edits.substring(i, i + 1));
            pieces.region.presentationRows(pieces.emulator);
        }
        whole.region.reconcileRepaints(whole.emulator);
        pieces.region.reconcileRepaints(pieces.emulator);
        for (int row = 0; row < 5; row++) {
            assertEquals(whole.moving(row), pieces.moving(row));
            assertEquals(whole.region.stationary[row], pieces.region.stationary[row]);
            assertEquals(whole.live(row), pieces.live(row));
        }
    }

    @Test public void fullRepaintIsNotInventedScrollingAndResetDropsTransientState() {
        Fixture f = new Fixture();
        f.append("\033[Hrepaint");
        assertNull(f.region.moving);
        f.append("\033[1;5r\033[H\033[L");
        assertNotNull(f.region.moving);
        f.append("\033[?1049h");
        assertNull(f.region.moving);
        f.append("\033[L");
        assertNotNull(f.region.moving);
        f.emulator.resize(10, 8, 10, 10);
        assertNull(f.region.moving);
    }

    @Test public void boundedModelKeepsOnlyCurrentRegionDuringRepeatedEdits() {
        Fixture f = new Fixture();
        for (int i = 0; i < 100; i++) {
            f.append("\033[2;5r\033[2;1H\033[Lnew");
            f.region.reconcileRepaints(f.emulator);
            assertEquals(6, f.region.moving.length);
            assertNull(f.region.moving[0]);
            assertNull(f.region.moving[5]);
            assertEquals("AAAAAAAA", f.live(0));
            assertEquals("STATUS!!", f.live(5));
        }
    }
}
