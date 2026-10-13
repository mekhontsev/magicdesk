package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.Assert.*;

public final class PaperWindowLayoutTest {
    private static final PaperWindowLayout.Bounds AREA =
            new PaperWindowLayout.Bounds(0, 20, 1000, 620);

    private static List<PaperWindowLayout.Window> windows(final int... ids) {
        final List<PaperWindowLayout.Window> result = new ArrayList<>();
        for (final int id : ids) result.add(new PaperWindowLayout.Window(id, 400));
        return result;
    }

    @Test public void maximizedColumnFillsViewportAndRestoresPreferredWidth() {
        final var layout = new PaperWindowLayout();
        layout.update(windows(1, 2, 3), AREA, 10, 60, 2);
        final var order = new ArrayList<>(layout.bounds().keySet());
        layout.setMaximized(2, true);
        assertEquals(new PaperWindowLayout.Bounds(10, 30, 990, 610), layout.bounds().get(2));
        assertEquals(order, new ArrayList<>(layout.bounds().keySet()));
        assertEquals(400, layout.bounds().get(1).width());
        assertEquals(400, layout.bounds().get(3).width());
        layout.setMaximized(2, true);
        assertTrue(layout.isMaximized(2));
        layout.setMaximized(2, false);
        assertEquals(400, layout.bounds().get(2).width());
        assertTrue(layout.bounds().get(2).left() >= 70);
        assertTrue(layout.bounds().get(2).right() <= 930);
    }

    @Test public void maximizationSurvivesResizeAndMinimizeButNotTaskRemoval() {
        final var layout = new PaperWindowLayout();
        layout.update(windows(1, 2), AREA, 10, 60, 1);
        layout.setMaximized(1, true);
        var smaller = new PaperWindowLayout.Bounds(100, 30, 800, 530);
        layout.update(windows(1, 2), smaller, 10, 60, 1);
        assertEquals(new PaperWindowLayout.Bounds(110, 40, 790, 520), layout.bounds().get(1));
        layout.update(List.of(new PaperWindowLayout.Window(1, 400, true),
                new PaperWindowLayout.Window(2, 400)), smaller, 10, 60, 2);
        assertFalse(layout.bounds().containsKey(1));
        layout.update(windows(1, 2), smaller, 10, 60, 1);
        assertEquals(680, layout.bounds().get(1).width());
        layout.cycleWidth(1);
        assertFalse(layout.isMaximized(1));
        layout.setMaximized(1, true);
        layout.update(windows(2), smaller, 10, 60, 2);
        layout.update(windows(1, 2), smaller, 10, 60, 1);
        assertFalse(layout.isMaximized(1));
        assertEquals(400, layout.bounds().get(1).width());
    }

    @Test public void maximizedViewKeepsBothOuterGapsClearOfOverflowWindows() {
        final var layout = new PaperWindowLayout();
        final var tasks = windows(1, 2, 3, 4, 5);
        for (final var area : List.of(AREA, new PaperWindowLayout.Bounds(100, 30, 800, 530))) {
            layout.update(tasks, area, 10, 60, 3);
            layout.setMaximized(3, true);
            // Include another full-width column and peers on both sides.
            layout.setMaximized(1, true);
            layout.update(tasks, area, 10, 60, 3);
            final var selected = layout.bounds().get(3);
            for (final var peer : layout.bounds().values()) {
                assertTrue(peer.left() >= selected.left());
                assertTrue(peer.right() <= selected.right());
                assertEquals(selected.top(), peer.top());
                assertEquals(selected.bottom(), peer.bottom());
            }
            assertFalse(layout.update(tasks, area, 10, 60, 3));
            assertEquals(4, layout.select(3, 1, false));
            assertTrue(layout.bounds().get(4).left() >= area.left() + 70);
            assertTrue(layout.bounds().get(4).right() <= area.right() - 70);
            assertEquals(area.left() + 60, layout.bounds().get(1).right());
            layout.setMaximized(3, false);
            assertEquals(400, layout.bounds().get(3).width());
        }
    }

    @Test public void adjacentColumnsUseWorkAreaAndKeepTheirWidths() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        assertTrue(layout.update(windows(1, 2), AREA, 10, 60, 1));
        assertEquals(new PaperWindowLayout.Bounds(70, 30, 470, 610), layout.bounds().get(1));
        assertEquals(new PaperWindowLayout.Bounds(480, 30, 880, 610), layout.bounds().get(2));
        layout.update(windows(1, 2, 3), AREA, 10, 60, 1);
        assertEquals(List.of(1, 3, 2), new ArrayList<>(layout.bounds().keySet()));
        for (final var b : layout.bounds().values()) assertEquals(400, b.width());
    }

    @Test public void focusScrollsOnlyWhenNeededAndPreservesColumnOrder() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1, 2, 3), AREA, 10, 60, 1);
        final var before = layout.bounds();
        assertFalse(layout.update(windows(2, 1, 3), AREA, 10, 60, 2));
        assertEquals(before, layout.bounds());
        assertTrue(layout.update(windows(3, 1, 2), AREA, 10, 60, 3));
        assertEquals(List.of(1, 2, 3), new ArrayList<>(layout.bounds().keySet()));
        assertEquals(930, layout.bounds().get(3).right());
        assertFalse(layout.update(windows(3, 1, 2), AREA, 10, 60, 3));
        assertTrue(layout.update(windows(1, 3, 2), AREA, 10, 60, 1));
        assertEquals(70, layout.bounds().get(1).left());
    }

    @Test public void minimizingClosesGapAndRestorationKeepsOrderAndWidth() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1, 2, 3), AREA, 10, 60, 1);
        layout.cycleWidth(2);
        var minimized = List.of(new PaperWindowLayout.Window(1,400),
                new PaperWindowLayout.Window(2,400,true), new PaperWindowLayout.Window(3,400));
        assertTrue(layout.update(minimized, AREA, 10, 60, 1));
        assertEquals(List.of(1,3), new ArrayList<>(layout.bounds().keySet()));
        assertEquals(layout.bounds().get(1).right()+10, layout.bounds().get(3).left());
        assertEquals(3, layout.select(1,1,false));
        layout.update(windows(3,2,1), AREA,10,60,2);
        assertEquals(List.of(1,2,3), new ArrayList<>(layout.bounds().keySet()));
        assertEquals(430, layout.bounds().get(2).width());
        assertTrue(layout.bounds().get(2).left()>=70);
        assertTrue(layout.bounds().get(2).right()<=930);
        layout.update(List.of(new PaperWindowLayout.Window(1,400,true),
                new PaperWindowLayout.Window(2,400,true),new PaperWindowLayout.Window(3,400,true)),
                AREA,10,60,-1);
        assertTrue(layout.bounds().isEmpty());
        assertEquals(-1,layout.select(-1,1,false));
    }

    @Test public void navigationRevealsTargetAndLeavesOverflowAtNativeEdges() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1, 2, 3, 4, 5), AREA, 10, 60, 1);
        assertEquals(5, layout.select(4, 1, false));
        assertEquals(930, layout.bounds().get(5).right());
        assertEquals(60, layout.bounds().get(1).right());
        assertEquals(1, layout.select(1, -1, false));
        assertEquals(70, layout.bounds().get(1).left());
        assertEquals(940, layout.bounds().get(5).left());
        assertEquals(5, layout.select(5, 1, false));
    }

    @Test public void reorderingMovesIdentityRatherThanChangingFocusToItsNeighbor() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1, 2, 3), AREA, 10, 60, 1);
        assertEquals(2, layout.select(2, 1, true));
        assertEquals(List.of(1, 3, 2), new ArrayList<>(layout.bounds().keySet()));
        assertEquals(2, layout.select(2, -1, true));
        assertEquals(List.of(1, 2, 3), new ArrayList<>(layout.bounds().keySet()));
    }

    @Test public void unknownExplicitTargetNeverMovesAnotherWindow() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1, 2), AREA, 10, 60, 1);
        final var before = layout.bounds();
        assertEquals(-1, layout.select(99, 1, true));
        assertEquals(before, layout.bounds());
    }

    @Test public void navigationAvailabilityFollowsSelectionAndSkipsMinimizedColumns() {
        final var layout = new PaperWindowLayout();
        assertFalse(layout.canSelect(-1));
        assertFalse(layout.canSelect(1));
        layout.update(windows(1, 2, 3), AREA, 10, 60, 1);
        assertFalse(layout.canSelect(-1));
        assertTrue(layout.canSelect(1));
        layout.select(1, 1, false);
        assertTrue(layout.canSelect(-1));
        assertTrue(layout.canSelect(1));
        layout.update(List.of(new PaperWindowLayout.Window(1, 400, true),
                new PaperWindowLayout.Window(2, 400), new PaperWindowLayout.Window(3, 400)),
                AREA, 10, 60, 2);
        assertFalse(layout.canSelect(-1));
        layout.select(2, 1, false);
        assertTrue(layout.canSelect(-1));
        assertFalse(layout.canSelect(1));
        layout.update(windows(3), AREA, 10, 60, 3);
        assertFalse(layout.canSelect(-1));
        assertFalse(layout.canSelect(1));
    }

    @Test public void nativeFocusOnlyRequestsImmediateRevealForClippedColumns() {
        final var layout = new PaperWindowLayout();
        layout.update(windows(1, 2, 3), AREA, 10, 60, 1);
        assertFalse(layout.requiresReveal(1));
        assertFalse(layout.requiresReveal(2));
        assertTrue(layout.requiresReveal(3));
        assertFalse(layout.requiresReveal(99));
        layout.setMaximized(1, true);
        assertTrue(layout.requiresReveal(2));
        layout.update(List.of(new PaperWindowLayout.Window(1, 400),
                new PaperWindowLayout.Window(2, 400, true)), AREA, 10, 60, 1);
        assertFalse(layout.requiresReveal(2));
    }

    @Test public void widthCycleDoesNotResizePeers() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1, 2), AREA, 10, 60, 1);
        final int[] widths = {430, 573, 860, 286};
        for (final int expected : widths) {
            layout.cycleWidth(1);
            assertEquals(expected, layout.bounds().get(1).width());
            assertEquals(400, layout.bounds().get(2).width());
        }
    }

    @Test public void closingColumnsClampsScrollAndEmptySessionCanBeReused() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1, 2, 3), AREA, 10, 60, 3);
        assertTrue(layout.update(windows(1), AREA, 10, 60, -1));
        assertEquals(70, layout.bounds().get(1).left());
        assertTrue(layout.update(List.of(), AREA, 10, 60, -1));
        assertTrue(layout.bounds().isEmpty());
        assertEquals(-1, layout.select(-1, 1, false));
        layout.update(windows(7), AREA, 10, 60, 7);
        assertEquals(70, layout.bounds().get(7).left());
    }

    @Test public void viewportChangesClampWidthsWithoutLosingPreferredWidth() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1), AREA, 10, 60, 1);
        final var narrow = new PaperWindowLayout.Bounds(100, 50, 400, 500);
        assertTrue(layout.update(windows(1), narrow, 10, 60, 1));
        assertEquals(new PaperWindowLayout.Bounds(170, 60, 330, 490), layout.bounds().get(1));
        layout.update(windows(1), AREA, 10, 60, 1);
        assertEquals(400, layout.bounds().get(1).width());
    }

    @Test public void tinyViewportAndOversizedGapsRemainPositiveAndIdempotent() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        final var tiny = new PaperWindowLayout.Bounds(0, 0, 1, 1);
        assertTrue(layout.update(windows(1), tiny, 1000, 1000, 1));
        assertEquals(tiny, layout.bounds().get(1));
        assertFalse(layout.update(windows(1), tiny, 1000, 1000, 1));
        layout.clear();
        assertTrue(layout.bounds().isEmpty());
    }

    @Test public void activationKeepsNearestEdgePreviewAboveDistantColumns() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1, 2, 3, 4, 5), AREA, 10, 60, 3);
        final var before = layout.bounds();
        assertEquals(List.of(1, 5, 2, 4, 3), layout.activationOrder(3));
        assertEquals(List.of(5, 4, 3, 2, 1), layout.activationOrder(1));
        assertEquals(List.of(1, 2, 3, 4, 5), layout.activationOrder(5));
        assertEquals(before, layout.bounds());
        layout.update(List.of(new PaperWindowLayout.Window(1, 400),
                new PaperWindowLayout.Window(2, 400, true),
                new PaperWindowLayout.Window(3, 400)), AREA, 10, 60, 3);
        assertEquals(List.of(1, 3), layout.activationOrder(3));
        assertEquals(List.of(99), layout.activationOrder(99));
    }

    @Test public void invalidViewportDoesNotDestroyLastLayout() {
        final PaperWindowLayout layout = new PaperWindowLayout();
        layout.update(windows(1), AREA, 10, 60, 1);
        final var before = layout.bounds();
        assertFalse(layout.update(List.of(), new PaperWindowLayout.Bounds(0, 0, 0, 0), 10, 60, 1));
        assertEquals(before, layout.bounds());
    }

    @Test public void randomizedNavigationKeepsSelectedColumnInsideViewport() {
        final Random random = new Random(314159);
        final PaperWindowLayout layout = new PaperWindowLayout();
        final List<PaperWindowLayout.Window> windows = new ArrayList<>();
        for (int i = 0; i < 100; i++) windows.add(new PaperWindowLayout.Window(i, 1 + random.nextInt(2000)));
        layout.update(windows, AREA, 10, 60, 0);
        int selected = 0;
        for (int i = 0; i < 1000; i++) {
            selected = layout.select(selected, random.nextBoolean() ? 1 : -1, random.nextBoolean());
            if (random.nextBoolean()) layout.cycleWidth(selected);
            final var b = layout.bounds().get(selected);
            assertTrue(b.left() >= 70);
            assertTrue(b.right() <= 930);
            assertTrue(b.width() > 0);
            assertEquals(100, layout.bounds().size());
        }
    }
}
