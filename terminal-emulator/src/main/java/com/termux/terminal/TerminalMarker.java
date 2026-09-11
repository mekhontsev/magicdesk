package com.termux.terminal;

/** A zero-width text boundary. Rows own it, so ring-buffer recycling invalidates it. */
public final class TerminalMarker {
    final TerminalBuffer buffer;
    TerminalRow row;
    int column;

    TerminalMarker(TerminalBuffer buffer, TerminalRow row, int column) {
        this.buffer = buffer;
        move(row, column);
    }

    void move(TerminalRow target, int targetColumn) {
        release();
        row = target;
        column = targetColumn;
        target.addMarker(this);
    }

    public void release() {
        if (row != null) row.removeMarker(this);
        row = null;
    }

    public record Position(int column, int row) { }

    public Position position() {
        if (row == null) return null;
        for (int y = -buffer.getActiveTranscriptRows(); y < buffer.mScreenRows; y++) {
            if (buffer.mLines[buffer.externalToInternalRow(y)] == row) {
                return new Position(column, y);
            }
        }
        return null;
    }
}
