package io.github.mekhontsev.magicdesk;

/** Grid capacity is local to the Start viewport, including IME resizing. */
final class StartMenuLayout {
    private StartMenuLayout() { }

    static int columns(final int widthDp) {
        return Math.max(1, Math.min(4, widthDp / 100));
    }

    static int rows(final int bodyHeightDp) {
        // Pager and grid margins remain outside the fixed-height tiles.
        return Math.max(1, Math.min(6, (bodyHeightDp - 80) / 112));
    }
}
