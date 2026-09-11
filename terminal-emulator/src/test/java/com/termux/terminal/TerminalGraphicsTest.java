package com.termux.terminal;

import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;

public class TerminalGraphicsTest extends TerminalTestCase {
    private static final String ST = "\033\\";
    private static final String RED = "/wAA";

    private void kitty(String value) { enterString("\033_G" + value + ST); }
    private List<TerminalGraphics.Placement> visible() {
        return mTerminal.getGraphics().visible(mTerminal.getScreen(), -100, mTerminal.mRows);
    }
    private void red(int id) { kitty("a=T,f=24,s=1,v=1,C=1,i=" + id + ";" + RED); }

    private TerminalImage hit(float column, float row) {
        return mTerminal.getGraphics().imageAt(mTerminal.getScreen(), column, row, 10, 20);
    }

    public void testHitTestingClipsLayeringScrollbackAndBuffers() {
        withTerminalSized(20, 10);
        kitty("a=T,f=24,s=1,v=1,C=1,c=4,r=2,z=-1,i=1;" + RED);
        TerminalGraphics.Placement first = visible().get(0);
        assertSame(first.image, hit(0.5f, 0.5f));
        assertNull(hit(4, 0));
        first.clipLeft = 1;
        assertNull(hit(0.5f, 0.5f));
        assertSame(first.image, hit(1.5f, 0.5f));
        kitty("a=T,f=24,s=1,v=1,C=1,c=4,r=2,z=0,i=2;AAAA");
        assertSame(visible().get(1).image, hit(1.5f, 0.5f));
        enterString("\033[10;1H\n");
        assertSame(visible().get(1).image, hit(1.5f, -0.5f));
        enterString("\033[?1049h");
        assertNull(hit(1.5f, 0.5f));
        enterString("\033[?1049l");
        assertNotNull(hit(1.5f, -0.5f));
        assertNull(hit(Float.NaN, 0));
        assertNull(hit(-1, 0));
        assertNull(hit(1, -100));
    }

    public void testHitTestingVirtualPlaceholdersAndLetterboxing() {
        withTerminalSized(20, 10);
        kitty("a=T,U=1,f=24,s=1,v=1,c=4,r=1,i=42;" + RED);
        enterString("\033[38;5;42m\udbfb\udeee\u0305\u0305\udbfb\udeee\udbfb\udeee\udbfb\udeee\033[0m");
        assertNull(hit(0.5f, 0.5f)); // Square raster centered inside a 40x20 cell rectangle.
        TerminalImage image = mTerminal.getGraphics().virtualPlacement(mTerminal.getScreen(), 42, 0).image;
        assertSame(image, hit(1.5f, 0.5f));
        assertSame(image, hit(2.5f, 0.5f)); // Inherits the previous placeholder's address.
        assertNull(hit(3.5f, 0.5f));
        enterString("\033[H ");
        assertNull(hit(0.5f, 0.5f));
        enterString("\033[2J\033[H\033[38;5;42m\udbfb\udeee\u0305\u030d\033[0m");
        assertSame(image, hit(0.5f, 0.5f)); // Moved by tmux, not by the image prototype's origin.
    }

    public void testInlineRgbAndChunkedRgba() {
        withTerminalSized(20, 10);
        red(1);
        assertEquals(0xffff0000, visible().get(0).image.pixelAt(0, 0));
        kitty("a=T,f=32,s=2,v=1,C=1,i=2,m=1;/wAA/wAA");
        assertEquals(1, visible().size());
        kitty("m=0;/4A=");
        assertEquals(2, visible().size());
        assertEquals(0x800000ff, visible().get(1).image.pixelAt(1, 0));
        assertEquals("\033_Gi=1;OK" + ST + "\033_Gi=2;OK" + ST, mOutput.getOutputAndClear());
    }

    public void testEveryByteBoundaryAndNoPayloadLeak() {
        withTerminalSized(10, 5);
        String sequence = "\033_Ga=T,f=24,s=1,v=1,C=1,i=1;" + RED + ST;
        for (int i = 0; i < sequence.length(); i++) enterString(sequence.substring(i, i + 1));
        assertEquals(1, visible().size());
        enterString("\033_G" + "a".repeat(10000) + ST + "OK");
        assertLineIs(0, "OK        ");
        enterString("\033Pq!999999999999999999~" + ST + "Z");
        assertLineIs(0, "OKZ       ");
    }

    public void testQueryDoesNotStoreOrDisplay() {
        withTerminalSized(10, 5);
        kitty("a=q,f=24,s=1,v=1,i=17;" + RED);
        assertEquals("\033_Gi=17;OK" + ST, mOutput.getOutputAndClear());
        assertEquals(0, mTerminal.getGraphics().imageCount());
        assertTrue(visible().isEmpty());
    }

    public void testUnsupportedFeaturesAndMalformedImages() {
        withTerminalSized(10, 5);
        for (String options : new String[] {"t=f", "t=s", "U=1", "a=f", "f=99", "s=99999999", "o=x"}) {
            kitty("i=9," + options + ";" + RED);
            String response = mOutput.getOutputAndClear();
            assertTrue(response, response.contains(";E"));
        }
        assertTrue(visible().isEmpty());
        kitty("a=T,f=24,s=1,v=1,i=9;q%invalid");
        assertTrue(mOutput.getOutputAndClear().contains(";EINVAL"));
        kitty("a=q,f=100,i=9;AAAA");
        assertTrue(mOutput.getOutputAndClear().contains(";ENOTSUP"));
    }

    public void testNamedPlacementsAndDeletion() {
        withTerminalSized(10, 5);
        kitty("f=24,s=1,v=1,i=8;" + RED);
        kitty("a=p,i=8,p=2,C=1,c=2,r=2");
        enterString("\033[2;3H");
        kitty("a=p,i=8,p=2,C=1");
        assertEquals(1, visible().size());
        assertEquals(2f, visible().get(0).column);
        kitty("a=d,d=i,i=8,p=2");
        assertEquals(1, mTerminal.getGraphics().imageCount());
        assertTrue(visible().isEmpty());
        kitty("a=p,i=8,C=1");
        kitty("a=d,d=I,i=8");
        assertEquals(0, mTerminal.getGraphics().imageCount());
    }

    public void testTextEraseVersusClearAndAlternateBuffer() {
        withTerminalSized(10, 5);
        red(1);
        enterString("\033[2Ktext");
        assertEquals(1, visible().size());
        enterString("\033[?1049h");
        assertTrue(visible().isEmpty());
        red(2);
        enterString("\033[?1049l");
        assertEquals(1, visible().get(0).imageId);
        enterString("\033[?1049h");
        assertTrue(visible().isEmpty());
        enterString("\033[?1049l\033[2J");
        assertTrue(visible().isEmpty());
        mTerminal.reset();
        assertEquals(0, mTerminal.getGraphics().pixelCount());
    }

    public void testScrollbackResizeAndClearHistory() {
        withTerminalSized(10, 5);
        enterString("\033[2;3H"); red(1);
        enterString("\033[5;1H\n\n");
        assertEquals(-1f, visible().get(0).row);
        mTerminal.resize(10, 6, 13, 15);
        assertEquals(0f, visible().get(0).row);
        mTerminal.resize(12, 6, 13, 15);
        assertEquals(2f, visible().get(0).column);
        enterString("\033[6;1H" + "\n".repeat(10));
        enterString("\033[3J");
        assertTrue(visible().isEmpty());
    }

    public void testSixelPaletteRepeatAndTransparency() {
        withTerminalSized(20, 10);
        enterString("\033P0;1q\"1;1;12;6#1;2;100;0;0!12~" + ST);
        TerminalImage image = visible().get(0).image;
        assertEquals(12, image.width); assertEquals(6, image.height);
        for (int y = 0; y < image.height; y++) for (int x = 0; x < image.width; x++)
            assertEquals(0xffff0000, image.pixelAt(x, y));
        assertEquals(1, mTerminal.getCursorRow());
        enterString("\033P0;1q#2;2;0;100;0@" + ST);
        image = visible().get(1).image;
        assertEquals(0xff00ff00, image.pixelAt(0, 0));
        assertEquals(0, image.pixelAt(0, 1));
    }

    public void testSixelPartialTextErase() {
        withTerminalSized(20, 10);
        enterString("\033Pq#1;2;100;0;0!39~" + ST);
        enterString("\033[1;2HX");
        assertEquals(2, visible().size());
        for (TerminalGraphics.Placement p : visible())
            assertTrue(p.column + p.clipRight <= 1 || p.column + p.clipLeft >= 2);
    }

    public void testSixelLimitsAndRecovery() {
        withTerminalSized(20, 10);
        for (String body : new String[] {"!999999~", "\"1;1;4096;4097~", "#999~", "#1;2;101;0;0~"})
            enterString("\033Pq" + body + ST);
        assertTrue(visible().isEmpty());
        enterString("\033Pq#1;2;100;0;0~" + ST);
        assertEquals(1, visible().size());
    }

    public void testCompressionAndQuota() {
        withTerminalSized(20, 10);
        Deflater deflater = new Deflater();
        deflater.setInput(new byte[] {(byte)255, 0, 0}); deflater.finish();
        byte[] compressed = new byte[100];
        int count = deflater.deflate(compressed); deflater.end();
        kitty("a=T,f=24,s=1,v=1,C=1,i=9,o=z;" + Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(compressed, count)));
        assertEquals(0xffff0000, visible().get(0).image.pixelAt(0, 0));
        for (int i = 1; i <= 200; i++) red(i);
        assertTrue(mTerminal.getGraphics().imageCount() <= TerminalGraphics.MAX_IMAGES);
        assertTrue(visible().size() <= TerminalGraphics.MAX_PLACEMENTS);
        assertTrue(mTerminal.getGraphics().pixelCount() <= TerminalImage.MAX_PIXELS);
    }

    public void testSixelCapabilityQueries() {
        withTerminalSized(10, 5);
        assertEnteringStringGivesResponse("\033[c", "\033[?64;1;2;4;6;9;15;18;21;22c");
        assertEnteringStringGivesResponse("\033[?1;1;0S", "\033[?1;0;256S");
        assertEnteringStringGivesResponse("\033[?2;4;0S", "\033[?2;0;4096;4096S");
    }

    public void testVirtualPlacementSurvivesMultiplexerRedraw() {
        withTerminalSized(10, 5);
        kitty("a=T,U=1,f=24,s=1,v=1,c=2,r=2,i=42;" + RED);
        assertTrue(visible().isEmpty());
        TerminalGraphics.Placement prototype = mTerminal.getGraphics().virtualPlacement(mTerminal.getScreen(), 42, 0);
        assertNotNull(prototype);
        assertEquals(0, mTerminal.getCursorRow());
        enterString("\033[38;5;42m\udbfb\udeee\u0305\u0305\udbfb\udeee\033[0m");
        KittyImagePlaceholder cell = new KittyImagePlaceholder();
        readCell(cell, 0, 0);
        assertEquals(42, cell.imageId); assertEquals(0, cell.row); assertEquals(0, cell.column);
        readCell(cell, 1, 0);
        assertEquals(42, cell.imageId); assertEquals(1, cell.column);
        kitty("a=d");
        enterString("\033[2J");
        assertSame(prototype, mTerminal.getGraphics().virtualPlacement(mTerminal.getScreen(), 42, 0));
        kitty("a=d,d=I,i=42");
        assertNull(mTerminal.getGraphics().virtualPlacement(mTerminal.getScreen(), 42, 0));
    }

    public void testPlaceholderPlacementColorCopyAndReflow() {
        withTerminalSized(8, 5);
        enterString("\033[38;2;0;0;42;58;2;0;0;9m\udbfb\udeee\u0305\u0305\u030e\udbfb\udeee\033[0m");
        KittyImagePlaceholder cell = new KittyImagePlaceholder();
        readCell(cell, 0, 0);
        assertEquals(33554474L, cell.imageId); assertEquals(9, cell.placementId);
        readCell(cell, 1, 0);
        assertEquals(33554474L, cell.imageId); assertEquals(1, cell.column);
        mTerminal.getScreen().blockCopy(0, 0, 2, 1, 2, 1);
        cell.reset(); readCell(cell, 2, 1);
        assertEquals(9, cell.placementId);
        mTerminal.resize(12, 5, 13, 15);
        cell.reset(); readCell(cell, 2, 1);
        assertEquals(9, cell.placementId); assertEquals(33554474L, cell.imageId);
        enterString("\033[1;4H\033[38;5;42m\udbfb\udeee\u0305\u0305");
        cell.reset(); readCell(cell, 3, 0);
        assertEquals(0, cell.placementId);
    }

    private void readCell(KittyImagePlaceholder cell, int column, int row) {
        TerminalRow line = mTerminal.getScreen().mLines[mTerminal.getScreen().externalToInternalRow(row)];
        assertTrue(cell.read(line, column, line.findStartOfColumn(column), line.findStartOfColumn(column + 1)));
    }

    public void testCopyIsNotScrollAndScrollMarginsClip() {
        withTerminalSized(10, 6);
        enterString("\033[3;1H");
        kitty("a=T,f=24,s=1,v=1,c=1,r=1,C=1,i=1;" + RED);
        mTerminal.getScreen().blockCopy(0, 2, 10, 1, 0, 3);
        assertEquals(2f, visible().get(0).row);
        mTerminal.getScreen().moveLines(0, 2, 10, 2, 3);
        assertEquals(3f, visible().get(0).row);
        enterString("\033[2;5r\033[5;1H\n");
        assertEquals(2f, visible().get(0).row);
    }

    public void testSixelTallerThanViewportIsNotPrematurelyClipped() {
        withTerminalSized(10, 4);
        enterString("\033Pq#1;2;100;0;0" + "!10~-".repeat(20) + ST);
        TerminalGraphics.Placement p = visible().get(0);
        assertEquals(8f, p.clipBottom);
        assertTrue(p.row < 0);
        assertTrue(p.row + p.clipBottom > 0);
    }

    public void testDeletionDoesNotFreeUnrelatedPreloadedImages() {
        withTerminalSized(10, 5);
        kitty("f=24,s=1,v=1,i=20;" + RED);
        red(1);
        kitty("a=d,d=I,i=1");
        assertNotNull(mTerminal.getGraphics().image(20));
        red(2);
        enterString("\033[2J\033[?1049h\033[?1049l\033[3J");
        assertNotNull(mTerminal.getGraphics().image(20));
        kitty("a=p,i=20,C=1");
        assertEquals(20, visible().get(0).imageId);
    }

    public void testBudgetEvictsImagesAndPlacementsTogether() {
        withTerminalSized(10, 5);
        TerminalGraphics graphics = mTerminal.getGraphics();
        TerminalImage large = new TerminalImage(4096, 4096) {
            @Override public int pixelAt(int x, int y) { return 0; }
        };
        graphics.put(1, large);
        graphics.place(mTerminal.getScreen(), 1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 0, false);
        graphics.put(2, large);
        assertNull(graphics.image(1));
        assertEquals(TerminalImage.MAX_PIXELS, graphics.pixelCount());
        assertTrue(visible().isEmpty());
        graphics.setByteBudget(128L * 1024 * 1024);
        graphics.put(3, large);
        assertEquals(2, graphics.imageCount());
        graphics.setByteBudget(64L * 1024 * 1024);
        assertEquals(1, graphics.imageCount());
    }

    public void testSavedCursorRestoresPlaceholderPlacementColor() {
        withTerminalSized(10, 5);
        enterString("\033[38;5;42;58;5;9m\0337\033[0m\0338\udbfb\udeee\u0305\u0305");
        KittyImagePlaceholder cell = new KittyImagePlaceholder();
        readCell(cell, 0, 0);
        assertEquals(42, cell.imageId);
        assertEquals(9, cell.placementId);
        mTerminal.reset();
        enterString("\0338\033[38;5;42m\udbfb\udeee\u0305\u0305");
        cell.reset(); readCell(cell, 0, 0);
        assertEquals(0, cell.placementId);
    }

    public void testInvalidFlagsAndCancelledTransferRecover() {
        withTerminalSized(10, 5);
        for (String flag : new String[] {"m=2", "C=2", "U=2", "q=3"}) {
            kitty("a=T,f=24,s=1,v=1,i=1," + flag + ";" + RED);
            assertTrue(mOutput.getOutputAndClear().contains(";EINVAL"));
        }
        kitty("a=T,f=24,s=1,v=1,C=1,i=1,m=1;/wAA");
        enterString("\033_Gm=0;\030");
        red(2);
        assertEquals(1, visible().size());
        assertEquals(2, visible().get(0).imageId);
    }
}
