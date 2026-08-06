package io.github.mekhontsev.magicdesk;

import java.util.Objects;

final class DesktopPlacement {
    final int column;
    final int row;
    final int columnSpan;
    final int rowSpan;

    DesktopPlacement(
            final int column,
            final int row,
            final int columnSpan,
            final int rowSpan) {
        this.column = Math.max(0, column);
        this.row = Math.max(0, row);
        this.columnSpan = Math.max(1, columnSpan);
        this.rowSpan = Math.max(1, rowSpan);
    }

    DesktopPlacement withPosition(final int newColumn, final int newRow) {
        return new DesktopPlacement(
                newColumn, newRow, columnSpan, rowSpan);
    }

    DesktopPlacement withSpan(final int newColumnSpan, final int newRowSpan) {
        return new DesktopPlacement(
                column, row, newColumnSpan, newRowSpan);
    }

    boolean fits(final int columns, final int rows) {
        return column + columnSpan <= columns
                && row + rowSpan <= rows;
    }

    boolean intersects(final DesktopPlacement other) {
        return column < other.column + other.columnSpan
                && column + columnSpan > other.column
                && row < other.row + other.rowSpan
                && row + rowSpan > other.row;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DesktopPlacement)) {
            return false;
        }
        final DesktopPlacement placement = (DesktopPlacement) other;
        return column == placement.column
                && row == placement.row
                && columnSpan == placement.columnSpan
                && rowSpan == placement.rowSpan;
    }

    @Override
    public int hashCode() {
        return Objects.hash(column, row, columnSpan, rowSpan);
    }
}
