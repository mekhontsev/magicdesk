package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TerminalViewportTest {
    private TerminalViewport viewport() {
        TerminalViewport view = new TerminalViewport();
        view.metrics(10, 10, 6);
        view.resize(212, 112);
        return view;
    }

    @Test public void partialRowsHaveOneGeometryForTextImagesAndSelection() {
        TerminalViewport view = viewport();
        view.scroll(-13, 20);
        assertEquals(-2, view.topRow());
        assertEquals(7, view.rowOffset(), 0);
        assertEquals(11, view.visibleRows());
        assertEquals(-2, view.transcriptRowAt(8));
        assertEquals(-1, view.transcriptRowAt(9));
        assertEquals(8, view.transcriptRowAt(105));
        assertEquals(0, view.screenRowAt(9));
        assertEquals(9, view.rowBottomY(-2), 0);
        assertEquals(109, view.rowBottomY(8), 0);
        assertEquals(26, view.columnX(2), 0);
    }

    @Test public void fractionalHistorySurvivesOutputUntilEvicted() {
        TerminalViewport view = viewport();
        view.scroll(-13, 20);
        view.outputChanged(3, 20);
        assertEquals(-5, view.topRow());
        assertEquals(7, view.rowOffset(), 0);
        view.outputChanged(30, 20);
        assertEquals(-20, view.topRow());
        assertEquals(0, view.rowOffset(), 0);
        view.scroll(1, 20);
        assertEquals(1, view.rowOffset(), 0);
        view.clamp(0);
        assertTrue(view.isLive());
    }

    @Test public void fontAndImeGeometryDoNotOwnHistoryOrSessionLifetime() {
        TerminalViewport view = viewport();
        view.jumpTo(-5, 20);
        view.scroll(2.5f, 20);
        view.metrics(20, 20, 6);
        view.resize(212, 112);
        assertEquals(-5, view.topRow());
        assertEquals(5, view.rowOffset(), 0);
        assertEquals(10, view.columns());
        assertEquals(5, view.rows());
        assertFalse(view.resize(0, 0));
        assertEquals(5, view.rows());
        assertTrue(view.resize(212, 72));
        assertEquals(3, view.rows());
        assertTrue(view.resize(212, 112));
        assertEquals(-5, view.topRow());
    }

    @Test public void selectionMovesWithOutputAndIsClearedOnlyWhenEvicted() {
        TerminalViewport view = viewport();
        view.select(1, 1, 5, 3);
        view.outputChanged(3, 20);
        assertEquals(-2, view.startRow());
        assertEquals(0, view.endRow());
        assertTrue(view.hasSelection());
        view.outputChanged(30, 20);
        assertFalse(view.hasSelection());
    }

    @Test public void handlesNormalizeAndCannotCrossOrSkipPartialRows() {
        TerminalViewport view = viewport();
        view.jumpTo(-5, 20);
        view.scroll(7, 20);
        view.select(5, -3, 2, -5);
        view.moveHandle(true, 16, 9);
        assertEquals(1, view.startColumn());
        assertEquals(-5, view.startRow());
        view.moveHandle(false, 66, 105);
        assertEquals(5, view.endColumn());
        assertEquals(5, view.endRow());
        view.moveHandle(true, 1000, 1000);
        assertEquals(view.endRow(), view.startRow());
        assertEquals(view.endColumn(), view.startColumn());
        view.moveHandle(false, -100, -100);
        assertEquals(view.startRow(), view.endRow());
        assertEquals(view.startColumn(), view.endColumn());
    }

    @Test public void readingSelectionDoesNotMoveTheDragAnchor() {
        TerminalViewport view = viewport();
        view.select(8, 4, 3, 2);
        assertEquals(new TerminalViewport.Selection(3, 2, 8, 4), view.selection());
        assertEquals(8, view.startColumn());
        assertEquals(4, view.startRow());
        view.extendSelection(1, 1);
        assertEquals(new TerminalViewport.Selection(1, 1, 8, 4), view.selection());
    }
}
