package io.github.mekhontsev.magicdesk;

/** Window-local geometry and selection. No PTY, Android lifecycle or animation ownership. */
final class TerminalViewport {
    static final int NO_SELECTION = Integer.MIN_VALUE;
    private int columns = 80, rows = 24, topRow;
    private float rowOffset, cellWidth = 1, cellHeight = 1, padding;
    private int startColumn = NO_SELECTION, startRow = NO_SELECTION;
    private int endColumn = NO_SELECTION, endRow = NO_SELECTION;

    int columns() { return columns; }
    int rows() { return rows; }
    int topRow() { return topRow; }
    float rowOffset() { return rowOffset; }
    int visibleRows() { return rows + (rowOffset > 0 ? 1 : 0); }
    boolean isLive() { return topRow == 0 && rowOffset == 0; }
    boolean hasSelection() { return startRow != NO_SELECTION; }
    int startColumn() { return startColumn; }
    int startRow() { return startRow; }
    int endColumn() { return endColumn; }
    int endRow() { return endRow; }

    record Selection(int startColumn, int startRow, int endColumn, int endRow) { }

    Selection selection() {
        return position(startRow, startColumn) <= position(endRow, endColumn)
                ? new Selection(startColumn, startRow, endColumn, endRow)
                : new Selection(endColumn, endRow, startColumn, startRow);
    }

    void metrics(float width, float height, float inset) {
        if (!(width > 0) || !(height > 0) || !Float.isFinite(width) || !Float.isFinite(height))
            throw new IllegalArgumentException("Invalid terminal cell metrics");
        rowOffset = rowOffset / cellHeight * height;
        cellWidth = width;
        cellHeight = height;
        padding = inset;
    }

    boolean isMeasured(int width, int height) { return width > 2 * padding && height > 2 * padding; }

    boolean resize(int width, int height) {
        if (!isMeasured(width, height)) return false;
        int nextColumns = Math.max(2, (int) ((width - 2 * padding) / cellWidth));
        int nextRows = Math.max(2, (int) ((height - 2 * padding) / cellHeight));
        if (columns == nextColumns && rows == nextRows) return false;
        columns = nextColumns;
        rows = nextRows;
        clearSelection();
        return true;
    }

    void live() { topRow = 0; rowOffset = 0; }

    void jumpTo(int row, int historyRows) {
        topRow = row;
        rowOffset = 0;
        clamp(historyRows);
    }

    void scroll(float pixels, int historyRows) {
        float offset = rowOffset + pixels;
        int deltaRows = (int) Math.floor(offset / cellHeight);
        topRow += deltaRows;
        rowOffset = offset - deltaRows * cellHeight;
        clamp(historyRows);
        clearSelection();
    }

    void outputChanged(int scrolled, int historyRows) {
        if (topRow < 0 && scrolled > 0) topRow -= scrolled;
        if (hasSelection() && scrolled > 0) {
            startRow -= scrolled;
            endRow -= scrolled;
            if (startRow < -historyRows || endRow < -historyRows) clearSelection();
        }
        clamp(historyRows);
    }

    void clamp(int historyRows) {
        if (topRow < -historyRows) { topRow = -historyRows; rowOffset = 0; }
        else if (topRow >= 0) live();
    }

    int columnAt(float x) { return clamp((int) ((x - padding) / cellWidth), 0, columns - 1); }
    int screenRowAt(float y) { return clamp((int) ((y - padding) / cellHeight), 0, rows - 1); }
    float columnPosition(float x) { return (x - padding) / cellWidth; }
    float viewportRowAt(float y) { return (y - padding + rowOffset) / cellHeight; }
    int transcriptRowAt(float y) {
        return topRow + clamp((int) Math.floor(viewportRowAt(y)), 0, visibleRows() - 1);
    }
    float columnX(int column) { return padding + column * cellWidth; }
    float rowBottomY(int row) { return padding + (row - topRow + 1) * cellHeight - rowOffset; }

    void select(int firstColumn, int firstRow, int lastColumn, int lastRow) {
        startColumn = firstColumn;
        startRow = firstRow;
        endColumn = lastColumn;
        endRow = lastRow;
    }

    void extendSelection(int column, int row) { endColumn = column; endRow = row; }

    void clearSelection() { startColumn = startRow = endColumn = endRow = NO_SELECTION; }

    void normalizeSelection() {
        if (!hasSelection() || position(startRow, startColumn) <= position(endRow, endColumn)) return;
        int column = startColumn, row = startRow;
        startColumn = endColumn;
        startRow = endRow;
        endColumn = column;
        endRow = row;
    }

    void moveHandle(boolean start, float x, float y) {
        if (!hasSelection()) return;
        normalizeSelection();
        int column = clamp(start ? (int) Math.floor(columnPosition(x))
                : (int) Math.ceil(columnPosition(x)) - 1, 0, columns - 1);
        int row = topRow + clamp((int) Math.ceil(viewportRowAt(y)) - 1, 0, visibleRows() - 1);
        if (start) {
            boolean before = position(row, column) <= position(endRow, endColumn);
            startColumn = before ? column : endColumn;
            startRow = before ? row : endRow;
        } else {
            boolean after = position(row, column) >= position(startRow, startColumn);
            endColumn = after ? column : startColumn;
            endRow = after ? row : startRow;
        }
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static long position(int row, int column) { return ((long) row << 32) | (column & 0xFFFFFFFFL); }
}
