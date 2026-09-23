package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import org.junit.Test;

public final class DesktopShellLayoutTest {
    @Test public void externalTaskbarAndWorkAreaMatchExistingGeometry() {
        final DesktopShellLayout layout = layout(1920, 1080, 0, 0, 64, false);
        assertEquals(new ShellBounds(0, 0, 1920, 1016), layout.snapshot().workArea());
        final ShellLayout.Surface taskbar = layout.snapshot().surfaces().get(DesktopShellLayout.TASKBAR);
        assertEquals(new ShellBounds(0, 1016, 1920, 1080), taskbar.content());
        assertEquals(taskbar.content(), taskbar.paint());
        assertEquals(taskbar.paint(), taskbar.input());
        assertEquals(ShellSurface.Keyboard.NONE, taskbar.request().keyboard());
    }

    @Test public void phonePaintExtendsThroughNavigationButControlsDoNot() {
        final DesktopShellLayout layout = layout(1216, 2688, 147, 126, 169, false);
        final ShellLayout.Surface taskbar = layout.snapshot().surfaces().get(DesktopShellLayout.TASKBAR);
        assertEquals(new ShellBounds(0, 2393, 1216, 2562), taskbar.content());
        assertEquals(new ShellBounds(0, 2393, 1216, 2688), taskbar.paint());
        assertEquals(new ShellBounds(0, 147, 1216, 2393), layout.snapshot().workArea());
        assertEquals(layout.snapshot().workArea(), layout.snapshot().panelArea());
    }

    @Test public void autoHideChangesOnlyWindowReservationNotPopupOrTaskbarBounds() {
        final DesktopShellLayout layout = layout(1920, 1080, 0, 0, 64, false);
        final ShellLayout.Snapshot before = layout.snapshot();
        layout.update(new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0), 64, true);
        assertEquals(before.content(), layout.snapshot().workArea());
        assertEquals(before.panelArea(), layout.snapshot().panelArea());
        assertEquals(before.surfaces().get(DesktopShellLayout.TASKBAR).paint(),
                layout.snapshot().surfaces().get(DesktopShellLayout.TASKBAR).paint());
    }

    @Test public void stableInputsReuseSnapshotAndDensityChangeRecomputesIt() {
        final DesktopShellLayout layout = layout(1920, 1080, 0, 0, 64, false);
        final DesktopViewport viewport = new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0);
        final ShellLayout.Snapshot before = layout.snapshot();
        layout.update(viewport, 64, false);
        assertSame(before, layout.snapshot());
        layout.update(viewport, 77, false);
        assertEquals(1003, layout.snapshot().workArea().bottom());
        layout.release();
        assertEquals(0, layout.snapshot().exclusions().size());
        assertEquals(viewport.contentGeometry(), layout.snapshot().workArea());
        layout.update(viewport, 77, false);
        assertEquals(1, layout.snapshot().surfaces().size());
    }

    @Test public void degenerateViewportRetainsOnePixelWorkArea() {
        final DesktopShellLayout layout = layout(1, 1, 0, 0, 100, false);
        assertEquals(new ShellBounds(0, 0, 1, 1), layout.snapshot().workArea());
    }

    private static DesktopShellLayout layout(final int width, final int height,
            final int top, final int bottom, final int taskbar, final boolean autoHide) {
        final DesktopShellLayout layout = new DesktopShellLayout();
        layout.update(new DesktopViewport(0, 0, width, height, 0, top, 0, bottom), taskbar, autoHide);
        return layout;
    }
}
