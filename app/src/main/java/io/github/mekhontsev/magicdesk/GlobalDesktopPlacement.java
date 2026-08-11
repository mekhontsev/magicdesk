package io.github.mekhontsev.magicdesk;

import java.util.Objects;

/** Grid placement whose anchor is independent of the current grid size. */
final class GlobalDesktopPlacement {
    static final int SCALE = 10_000;

    final int x;
    final int y;
    final int columnSpan;
    final int rowSpan;

    GlobalDesktopPlacement(
            final int x,
            final int y,
            final int columnSpan,
            final int rowSpan) {
        this.x = clamp(x, 0, SCALE);
        this.y = clamp(y, 0, SCALE);
        this.columnSpan = Math.max(1, columnSpan);
        this.rowSpan = Math.max(1, rowSpan);
    }

    static GlobalDesktopPlacement from(
            final DesktopPlacement placement,
            final int columns,
            final int rows) {
        if (placement == null || columns <= 0 || rows <= 0) {
            return null;
        }
        final int columnSpan = Math.min(columns, placement.columnSpan);
        final int rowSpan = Math.min(rows, placement.rowSpan);
        return new GlobalDesktopPlacement(
                ratio(placement.column, columns - columnSpan),
                ratio(placement.row, rows - rowSpan),
                columnSpan,
                rowSpan);
    }

    DesktopPlacement resolve(final int columns, final int rows) {
        final int resolvedColumnSpan = Math.max(
                1, Math.min(columns, columnSpan));
        final int resolvedRowSpan = Math.max(
                1, Math.min(rows, rowSpan));
        return new DesktopPlacement(
                scaled(x, columns - resolvedColumnSpan),
                scaled(y, rows - resolvedRowSpan),
                resolvedColumnSpan,
                resolvedRowSpan);
    }

    private static int ratio(final int value, final int range) {
        if (range <= 0) {
            return 0;
        }
        return clamp((int) Math.round(
                (double) value * SCALE / range), 0, SCALE);
    }

    private static int scaled(final int value, final int range) {
        if (range <= 0) {
            return 0;
        }
        return clamp((int) Math.round(
                (double) value * range / SCALE), 0, range);
    }

    private static int clamp(
            final int value,
            final int minimum,
            final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GlobalDesktopPlacement)) {
            return false;
        }
        final GlobalDesktopPlacement placement =
                (GlobalDesktopPlacement) other;
        return x == placement.x
                && y == placement.y
                && columnSpan == placement.columnSpan
                && rowSpan == placement.rowSpan;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, columnSpan, rowSpan);
    }
}
