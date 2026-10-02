package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StartMenuLayoutTest {
    @Test
    public void narrowPhoneAndDesktopHaveIndependentGridCapacity() {
        assertEquals(3, StartMenuLayout.columns(332, 100, 64, 4));
        assertEquals(5, StartMenuLayout.columns(532, 100, 64, 4));
        assertEquals(4, StartMenuLayout.columns(332, 88, 64, 4));
    }

    @Test
    public void keyboardResizeKeepsOneScrollableRow() {
        assertEquals(5, StartMenuLayout.rows(430, 72, 4));
        assertEquals(2, StartMenuLayout.rows(180, 72, 4));
        assertEquals(1, StartMenuLayout.rows(40, 72, 4));
    }

    @Test
    public void largeViewportUsesActualMeasuredCellAndViewport() {
        assertEquals(19, StartMenuLayout.columns(2000, 100, 64, 4));
        assertEquals(26, StartMenuLayout.rows(2000, 72, 4));
        assertEquals(3, StartMenuLayout.rows(430, 110, 4));
    }

    @Test
    public void unmeasuredViewportRemainsValid() {
        assertEquals(1, StartMenuLayout.columns(0, 100, 64, 4));
        assertEquals(1, StartMenuLayout.rows(0, 72, 4));
    }

    @Test
    public void largeIconsFitGridPaddingAndSearchRows() {
        assertEquals(3, StartMenuLayout.columns(332, 80, 84, 4));
        assertEquals(4, StartMenuLayout.columns(332, 80, 44, 4));
        assertEquals(1, StartMenuLayout.columns(30, 80, 84, 4));
        assertEquals(76, StartMenuLayout.rowHeight(64));
        assertEquals(58, StartMenuLayout.rowHeight(24));
    }

    @Test public void fittedRowsLeaveLessThanOneCellUnused() {
        for (int height = 72; height < 1800; height++) {
            int rows = StartMenuLayout.rows(height, 72, 4);
            int used = rows * 76 - 4;
            org.junit.Assert.assertTrue(used <= height && height - used < 76);
        }
    }

}
