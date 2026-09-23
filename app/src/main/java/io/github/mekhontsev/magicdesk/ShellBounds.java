package io.github.mekhontsev.magicdesk;

/** Immutable, half-open geometry in one layout scope's logical pixels. */
record ShellBounds(int left, int top, int right, int bottom) {
    ShellBounds {
        if (right < left || bottom < top
                || (long) right - left > Integer.MAX_VALUE
                || (long) bottom - top > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid shell bounds");
        }
    }

    int width() { return right - left; }
    int height() { return bottom - top; }
    boolean isEmpty() { return right == left || bottom == top; }

    ShellBounds intersect(final ShellBounds other) {
        final int x = Math.max(left, other.left);
        final int y = Math.max(top, other.top);
        return new ShellBounds(x, y, Math.max(x, Math.min(right, other.right)),
                Math.max(y, Math.min(bottom, other.bottom)));
    }
}
