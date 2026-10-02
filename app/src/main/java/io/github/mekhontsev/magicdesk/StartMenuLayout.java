package io.github.mekhontsev.magicdesk;

/** Grid capacity is local to the Start viewport, including IME resizing. */
final class StartMenuLayout {
    private StartMenuLayout() { }

    static int columns(final int width, final int preferredWidth, final int minimumWidth, final int gap) {
        int desired = Math.round((width + gap) / (float) Math.max(1, preferredWidth + gap));
        int capacity = (width + gap) / Math.max(1, minimumWidth + gap);
        return Math.max(1, Math.min(desired, capacity));
    }

    static int rowHeight(final int iconSizeDp) {
        return Math.max(58, iconSizeDp + 12);
    }

    static int rows(final int viewportHeight, final int measuredCellHeight, final int gap) {
        return Math.max(1, (viewportHeight + gap) / Math.max(1, measuredCellHeight + gap));
    }
}
