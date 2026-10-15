package io.github.mekhontsev.magicdesk;

/** Semantic window commands shared by input, menus, Linux panels and automation. */
enum DesktopWindowAction {
    FULLSCREEN(1, false),
    RESTORE(2, true),
    SNAP_LEFT(3, true),
    SNAP_RIGHT(4, true),
    CLOSE(5, true),
    SNAP_TOP_LEFT(6, true),
    SNAP_TOP_RIGHT(7, true),
    SNAP_BOTTOM_LEFT(8, true),
    SNAP_BOTTOM_RIGHT(9, true),
    MAXIMIZE(10, true),
    UNMAXIMIZE(11, false),
    PAPER_TOGGLE(100, false),
    PAPER_PREVIOUS(101, false),
    PAPER_NEXT(102, false),
    PAPER_MOVE_LEFT(103, false),
    PAPER_MOVE_RIGHT(104, false),
    PAPER_WIDTH(105, false),
    PAPER_FLOAT(106, false);

    private final int mDiagnosticId;
    private final boolean mSupportsFullscreen;

    DesktopWindowAction(int diagnosticId, boolean supportsFullscreen) {
        mDiagnosticId = diagnosticId;
        mSupportsFullscreen = supportsFullscreen;
    }

    // Event-log identifier, not a command transport or dispatch key.
    int diagnosticId() { return mDiagnosticId; }

    boolean supportsFullscreenTask() { return mSupportsFullscreen; }
}
