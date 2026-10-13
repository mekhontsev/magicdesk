package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.List;
import java.util.Set;

public final class DesktopPaperWindowControllerTest {
    private static final WindowLayoutPolicy.Bounds AREA = new WindowLayoutPolicy.Bounds(0, 0, 1400, 1000);
    private static final WindowLayoutPolicy.Bounds ORIGINAL = new WindowLayoutPolicy.Bounds(100, 100, 600, 800);

    private WindowLayoutPolicy.Workspace workspace() {
        return new WindowLayoutPolicy.Workspace(6, AREA, List.of(
                new WindowLayoutPolicy.Window(1, true, false, ORIGINAL, null),
                new WindowLayoutPolicy.Window(2, true, false, ORIGINAL, null),
                new WindowLayoutPolicy.Window(3, true, false, ORIGINAL, null)), 1);
    }

    private WindowLayoutPolicy.Workspace acknowledge(WindowLayoutPolicy.Workspace workspace,
            WindowLayoutPolicy.Plan plan, Set<Integer> hidden) {
        return new WindowLayoutPolicy.Workspace(6, AREA, workspace.windows().stream().map(window -> {
            var placement = plan.placements().stream().filter(p -> p.taskId() == window.taskId()).findFirst();
            return new WindowLayoutPolicy.Window(window.taskId(), window.freeform(), hidden.contains(window.taskId()),
                    placement.map(WindowLayoutPolicy.Placement::bounds).orElse(window.bounds()),
                    placement.isPresent() ? (window.floatingBounds() == null ? window.bounds() : window.floatingBounds())
                            : window.floatingBounds());
        }).toList(), workspace.focusedTaskId());
    }

    @Test public void speculativePlanningCannotAdvanceLiveState() {
        var policy = new DesktopPaperWindowController(8, 64);
        var first = policy.plan(workspace(), WindowLayoutPolicy.Request.observe());
        var repeated = policy.plan(workspace(), WindowLayoutPolicy.Request.observe());
        assertFalse(policy.owns(1));
        assertEquals(first.placements(), repeated.placements());
        assertTrue(((DesktopPaperWindowController) first.continuation()).owns(1));
        first.validate(workspace());
    }

    @Test public void minimizeRemovesSpaceAndRevealRestoresRequestedColumn() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var policy = (DesktopPaperWindowController) first.continuation();
        var hidden = acknowledge(workspace(), first, Set.of(2));
        var minimize = policy.plan(hidden, WindowLayoutPolicy.Request.observe());
        assertTrue(minimize.placements().stream().noneMatch(p -> p.taskId() == 2));
        var minimized = (DesktopPaperWindowController) minimize.continuation();
        var reveal = minimized.plan(acknowledge(hidden, minimize, Set.of(2)), WindowLayoutPolicy.Request.reveal(2));
        var restored = acknowledge(hidden, reveal, Set.of());
        var selected = restored.requireWindow(2).bounds();
        assertTrue(selected.left() >= 72 && selected.right() <= AREA.right() - 72);
        assertTrue(((DesktopPaperWindowController) reveal.continuation()).canNavigate(-1));
        assertTrue(((DesktopPaperWindowController) reveal.continuation()).canNavigate(1));
    }

    @Test public void maximizeRestoreKeepsWidthAndNativeReleaseUsesOriginalBounds() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var policy = (DesktopPaperWindowController) first.continuation();
        var live = acknowledge(workspace(), first, Set.of());
        var maximize = policy.command(live, DesktopPaperWindowController.Command.MAXIMIZE, 1).plan();
        assertEquals(new WindowLayoutPolicy.Bounds(8, 8, 1392, 992),
                maximize.placements().stream().filter(p -> p.taskId() == 1).findFirst().orElseThrow().bounds());
        var maximized = (DesktopPaperWindowController) maximize.continuation();
        var full = acknowledge(live, maximize, Set.of());
        maximized = (DesktopPaperWindowController) maximized.plan(full,
                WindowLayoutPolicy.Request.observe()).continuation();
        var restore = maximized.command(full,
                DesktopPaperWindowController.Command.UNMAXIMIZE, 1).plan();
        var bounds = restore.placements().stream().filter(p -> p.taskId() == 1).findFirst().orElseThrow().bounds();
        assertEquals(500, bounds.right() - bounds.left());
        var release = policy.plan(live, WindowLayoutPolicy.Request.release());
        assertTrue(release.placements().stream().allMatch(p -> p.bounds().equals(ORIGINAL)
                && p.retention() == WindowLayoutPolicy.Retention.RELEASE_LAYOUT));
    }

    @Test public void floatingExclusionReleasesOwnershipAndCanRejoin() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var live = acknowledge(workspace(), first, Set.of());
        var policy = (DesktopPaperWindowController) first.continuation();
        var floating = policy.command(live, DesktopPaperWindowController.Command.FLOAT, 1).plan();
        assertTrue(floating.placements().stream().anyMatch(p -> p.taskId() == 1
                && p.retention() == WindowLayoutPolicy.Retention.RELEASE_LAYOUT && p.bounds().equals(ORIGINAL)));
        var excluded = (DesktopPaperWindowController) floating.continuation();
        assertFalse(excluded.owns(1));
        var rejoin = excluded.command(live, DesktopPaperWindowController.Command.FLOAT, 1).plan();
        assertTrue(((DesktopPaperWindowController) rejoin.continuation()).owns(1));
    }

    @Test public void fullscreenAndTransitioningWindowsAreNeverPlaced() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var live = acknowledge(workspace(), first, Set.of());
        var fullscreen = new WindowLayoutPolicy.Workspace(6, AREA, live.windows().stream()
                .map(w -> new WindowLayoutPolicy.Window(w.taskId(), w.taskId() != 2,
                        w.concealed(), w.bounds(), w.floatingBounds())).toList(), 1);
        var policy = (DesktopPaperWindowController) first.continuation();
        var plan = policy.plan(fullscreen, WindowLayoutPolicy.Request.observe());
        assertTrue(plan.placements().stream().noneMatch(p -> p.taskId() == 2));
        plan.validate(fullscreen);
    }

    @Test public void unchangedConstrainedObservationDoesNotLoop() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var policy = (DesktopPaperWindowController) first.continuation();
        // Android acknowledged but retained the original rectangles.
        var constrained = new WindowLayoutPolicy.Workspace(6, AREA, workspace().windows().stream()
                .map(w -> new WindowLayoutPolicy.Window(w.taskId(), true, false, ORIGINAL, ORIGINAL)).toList(), 1);
        var repeat = policy.plan(constrained, WindowLayoutPolicy.Request.observe());
        assertTrue(repeat.placements().isEmpty());
    }

    private WindowLayoutPolicy.Workspace resized(WindowLayoutPolicy.Workspace workspace,
            int id, WindowLayoutPolicy.Bounds bounds) {
        return new WindowLayoutPolicy.Workspace(6, workspace.workArea(), workspace.windows().stream()
                .map(w -> w.taskId() == id ? new WindowLayoutPolicy.Window(id, true, false,
                        bounds, w.floatingBounds()) : w).toList(), id);
    }

    @Test public void nativeCaptionTogglePreservesTilingGapsAndRestoresColumnWidth() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var live = acknowledge(workspace(), first, Set.of());
        var policy = (DesktopPaperWindowController) first.continuation();
        var nativeBounds = new WindowLayoutPolicy.Bounds(0, 0, 1400, 1040);
        var fullColumn = new WindowLayoutPolicy.Bounds(8, 8, 1392, 992);
        for (int cycle = 0; cycle < 2; cycle++) {
            policy = (DesktopPaperWindowController) policy.plan(live,
                    WindowLayoutPolicy.Request.observe()).continuation();
            var nativeWorkspace = resized(live, 1, nativeBounds);
            var maximize = policy.plan(nativeWorkspace, WindowLayoutPolicy.Request.observe());
            assertFalse(policy.maximized(1));
            var maximized = (DesktopPaperWindowController) maximize.continuation();
            assertTrue(maximized.maximized(1));
            live = acknowledge(nativeWorkspace, maximize, Set.of());
            assertEquals(fullColumn, live.requireWindow(1).bounds());
            assertEquals(ORIGINAL, live.requireWindow(1).floatingBounds());
            // An acknowledgement is not a new native click. Repeated old native
            // geometry must neither toggle nor submit another correction.
            var stale = maximized.plan(nativeWorkspace, WindowLayoutPolicy.Request.observe());
            assertTrue(stale.placements().isEmpty());
            assertTrue(((DesktopPaperWindowController) stale.continuation()).maximized(1));
            maximized = (DesktopPaperWindowController) maximized.plan(live,
                    WindowLayoutPolicy.Request.observe()).continuation();
            // Android's second caption click expands to native bounds again.
            var secondClick = resized(live, 1, nativeBounds);
            var restore = maximized.plan(secondClick, WindowLayoutPolicy.Request.observe());
            policy = (DesktopPaperWindowController) restore.continuation();
            assertFalse(policy.maximized(1));
            live = acknowledge(secondClick, restore, Set.of());
            var restoredBounds = live.requireWindow(1).bounds();
            assertEquals(500, restoredBounds.right() - restoredBounds.left());
            assertEquals(8, restoredBounds.top());
            assertEquals(992, restoredBounds.bottom());
            assertEquals(ORIGINAL, live.requireWindow(1).floatingBounds());
        }
    }

    @Test public void nativeWidthChangeBecomesColumnWidth() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var live = acknowledge(workspace(), first, Set.of());
        var policy = (DesktopPaperWindowController) first.continuation();
        policy = (DesktopPaperWindowController) policy.plan(live, WindowLayoutPolicy.Request.observe()).continuation();
        var nativeWorkspace = resized(live, 1, new WindowLayoutPolicy.Bounds(72, 8, 772, 992));
        var resize = policy.plan(nativeWorkspace, WindowLayoutPolicy.Request.observe());
        var result = acknowledge(nativeWorkspace, resize, Set.of());
        assertEquals(700, result.requireWindow(1).bounds().right() - result.requireWindow(1).bounds().left());
        assertEquals(780, result.requireWindow(2).bounds().left());
        assertEquals(ORIGINAL, result.requireWindow(1).floatingBounds());
    }

    @Test public void explicitResizeChangesWidthWithoutLeavingTiling() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var live = acknowledge(workspace(), first, Set.of());
        var policy = (DesktopPaperWindowController) first.continuation();
        var resize = policy.plan(live, WindowLayoutPolicy.Request.place(new WindowLayoutPolicy.Placement(
                1, new WindowLayoutPolicy.Bounds(200, 200, 900, 700), WindowLayoutPolicy.Retention.FLOATING)));
        var result = acknowledge(live, resize, Set.of()).requireWindow(1);
        assertEquals(new WindowLayoutPolicy.Bounds(72, 8, 772, 992), result.bounds());
        assertEquals(ORIGINAL, result.floatingBounds());
        assertTrue(((DesktopPaperWindowController) resize.continuation()).owns(1));
    }

    @Test public void resizeExitsMaximizeAndOwnAcknowledgementRetainsRequestedWidth() {
        var first = new DesktopPaperWindowController(8, 64).plan(workspace(), WindowLayoutPolicy.Request.observe());
        var live = acknowledge(workspace(), first, Set.of());
        var policy = (DesktopPaperWindowController) first.continuation();
        var maximize = policy.command(live, DesktopPaperWindowController.Command.MAXIMIZE, 1).plan();
        live = acknowledge(live, maximize, Set.of());
        policy = (DesktopPaperWindowController) maximize.continuation();
        var resize = policy.plan(live, WindowLayoutPolicy.Request.place(new WindowLayoutPolicy.Placement(
                1, new WindowLayoutPolicy.Bounds(100, 100, 750, 800), WindowLayoutPolicy.Retention.FLOATING)));
        live = acknowledge(live, resize, Set.of());
        policy = (DesktopPaperWindowController) resize.continuation();
        var settled = policy.plan(live, WindowLayoutPolicy.Request.observe());
        policy = (DesktopPaperWindowController) settled.continuation();
        assertFalse(policy.maximized(1));
        assertTrue(settled.placements().isEmpty());
        assertEquals(650, live.requireWindow(1).bounds().right() - live.requireWindow(1).bounds().left());
    }
}
