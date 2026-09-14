package com.termux.terminal;

public final class TerminalFrameTest extends TerminalTestCase {
    public void testFrameFreezesMetadataAndSharesRasterWithoutRetainingHistory() {
        withTerminalSized(8, 6).enterString("one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\nseven");
        TerminalGraphics graphics = mTerminal.getGraphics();
        TerminalBuffer screen = mTerminal.getScreen();
        TerminalImage image = new TerminalImage(1, 1) {
            @Override public int pixelAt(int x, int y) { return 0xff00ff00; }
        };
        long id = graphics.put(1, image);
        TerminalGraphics.Placement live = graphics.place(screen, id, 1, 1, 1, 2, 3, 0, 0, 1, 1, 0, false);
        TerminalFrame frame = TerminalFrame.capture(mTerminal, -1, 7);
        assertEquals(7, frame.rows.length);
        assertNull(frame.row(-2));
        assertNull(frame.row(6));
        assertSame(image, frame.images.get(0).image);
        int color = frame.colors[0];
        mTerminal.mColors.mCurrentColors[0] ^= 0xffffff;
        live.row = 5;
        live.clipTop = 2;
        graphics.remove(id);
        assertEquals(color, frame.colors[0]);
        assertEquals(1f, frame.images.get(0).row);
        assertEquals(0f, frame.images.get(0).clipTop);
        assertSame(image, frame.images.get(0).image);
    }

    public void testMovingLayerSharesFrameMetadataAndNeverMutatesCommittedCells() {
        withTerminalSized(8, 6).enterString("AAAA");
        TerminalFrame frame = TerminalFrame.capture(mTerminal, 0, 6);
        TerminalRow[] moving = new TerminalRow[6];
        moving[0] = frame.row(0).snapshot();
        moving[0].setChar(0, 'Z', TextStyle.NORMAL);
        TerminalFrame animation = frame.withRows(moving);
        assertSame(frame.colors, animation.colors);
        assertSame(frame.images, animation.images);
        assertEquals('A', frame.row(0).mText[0]);
        assertEquals('Z', animation.row(0).mText[0]);
        assertEquals('A', mTerminal.getSelectedText(0, 0, 0, 0).charAt(0));
    }

    public void testVirtualLookupPreservesInsertionOrderAndPlacementIdentity() {
        withTerminalSized(8, 6);
        TerminalGraphics graphics = mTerminal.getGraphics();
        long id = graphics.put(1, new TerminalImage(1, 1) {
            @Override public int pixelAt(int x, int y) { return 0; }
        });
        graphics.place(mTerminal.getScreen(), id, 2, 0, 0, 1, 1, 0, 0, 1, 1, 8, false).virtual = true;
        graphics.place(mTerminal.getScreen(), id, 3, 0, 0, 1, 1, 0, 0, 1, 1, -8, false).virtual = true;
        TerminalFrame frame = TerminalFrame.capture(mTerminal, 0, 6);
        assertEquals(2, frame.virtualPlacement(id, 0).placementId);
        assertEquals(3, frame.virtualPlacement(id, 3).placementId);
        assertNull(frame.virtualPlacement(id, 4));
    }
}
