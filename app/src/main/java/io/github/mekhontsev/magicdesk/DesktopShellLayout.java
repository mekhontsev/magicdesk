package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Desktop shell policy expressed as layout intents; contains no Android window operations. */
final class DesktopShellLayout {
    static final String TASKBAR = "taskbar";
    private final ShellLayout mLayout = new ShellLayout();
    private DesktopViewport mViewport;
    private int mTaskbarHeight;
    private boolean mAutoHide;

    void update(final DesktopViewport viewport, final int requestedHeight, final boolean autoHide) {
        final int height = Math.max(1, Math.min(requestedHeight, viewport.contentHeight()));
        if (viewport.equals(mViewport) && height == mTaskbarHeight && autoHide == mAutoHide) {
            return;
        }
        final ShellSurface taskbar = new ShellSurface(TASKBAR, true, ShellSurface.Layer.TOP,
                ShellSurface.Keyboard.NONE,
                new ShellSurface.Placement(ShellSurface.Reference.CONTENT,
                        ShellSurface.LEFT | ShellSurface.RIGHT | ShellSurface.BOTTOM,
                        0, height, ShellSurface.Margins.NONE),
                new ShellSurface.Margins(0, 0, 0, viewport.insetBottom()),
                ShellSurface.Input.PAINT,
                List.of(ShellReservation.exclusive(ShellReservation.Edge.BOTTOM, height, !autoHide)));
        mLayout.commit(viewport.outputGeometry(), viewport.contentGeometry(), List.of(taskbar));
        mViewport = viewport;
        mTaskbarHeight = height;
        mAutoHide = autoHide;
    }

    ShellLayout.Snapshot snapshot() { return mLayout.snapshot(); }

    void release() {
        mLayout.clear();
        mViewport = null;
    }
}
