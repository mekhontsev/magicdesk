package io.github.mekhontsev.magicdesk;

/** Computes taskbar capacity while reserving one slot for overflow. */
final class TaskbarOverflowPolicy {
    private TaskbarOverflowPolicy() {
    }

    static int visibleItemCount(
            final int itemCount,
            final int availableWidth,
            final int itemWidth) {
        if (itemCount <= 0) {
            return 0;
        }
        if (availableWidth <= 0 || itemWidth <= 0) {
            return itemCount;
        }
        final int capacity = Math.max(1, availableWidth / itemWidth);
        if (itemCount <= capacity) {
            return itemCount;
        }
        return Math.max(0, capacity - 1);
    }
}
