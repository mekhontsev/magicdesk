package com.termux.terminal;

import java.util.List;

/**
 * One synchronous presentation read, captured on the emulator's owning thread.
 * Cell rows are borrowed until the next emulator edit; retained animation rows must
 * be snapshots. Palette and placement metadata are frozen; image rasters are shared.
 */
public final class TerminalFrame {
    final int columns, screenRows, firstRow;
    final int cursorRow, cursorColumn, cursorStyle;
    final boolean cursorVisible, reverseVideo;
    final int[] colors;
    final TerminalRow[] rows;
    final List<ImagePlacement> images;

    private TerminalFrame(TerminalEmulator emulator, int top, int count) {
        columns = emulator.mColumns;
        screenRows = emulator.mRows;
        cursorRow = emulator.getCursorRow();
        cursorColumn = emulator.getCursorCol();
        cursorStyle = emulator.getCursorStyle();
        cursorVisible = emulator.shouldCursorBeVisible();
        reverseVideo = emulator.isReverseVideo();
        colors = emulator.mColors.mCurrentColors.clone();
        TerminalBuffer screen = emulator.getScreen();
        firstRow = Math.max(-screen.getActiveTranscriptRows(), top);
        int end = Math.min(screenRows, top + count);
        rows = new TerminalRow[Math.max(0, end - firstRow)];
        for (int i = 0; i < rows.length; i++)
            rows[i] = screen.mLines[screen.externalToInternalRow(firstRow + i)];
        images = emulator.getGraphics().framePlacements(screen, firstRow, end);
    }

    private TerminalFrame(TerminalFrame frame, TerminalRow[] replacement) {
        columns = frame.columns;
        screenRows = frame.screenRows;
        firstRow = frame.firstRow;
        cursorRow = frame.cursorRow;
        cursorColumn = frame.cursorColumn;
        cursorStyle = frame.cursorStyle;
        cursorVisible = frame.cursorVisible;
        reverseVideo = frame.reverseVideo;
        colors = frame.colors;
        images = frame.images;
        rows = frame.rows.clone();
        for (int i = 0; i < rows.length; i++) {
            int row = firstRow + i;
            if (row >= 0 && row < replacement.length && replacement[row] != null) rows[i] = replacement[row];
        }
    }

    public static TerminalFrame capture(TerminalEmulator emulator, int top, int count) {
        if (count < 0 || count > emulator.mRows + 1)
            throw new IllegalArgumentException("Frame must be viewport bounded");
        return new TerminalFrame(emulator, top, count);
    }

    TerminalFrame withRows(TerminalRow[] replacement) { return new TerminalFrame(this, replacement); }

    TerminalRow row(int externalRow) {
        int index = externalRow - firstRow;
        return index < 0 || index >= rows.length ? null : rows[index];
    }

    ImagePlacement virtualPlacement(long id, int placementId) {
        for (ImagePlacement p : images) if (p.virtual && p.imageId == id
                && (placementId == 0 || p.placementId == placementId)) return p;
        return null;
    }

    /** Value metadata cannot move underneath another render pass or recorded row. */
    public static final class ImagePlacement {
        public final TerminalImage image;
        public final long imageId;
        public final int placementId, z, sourceX, sourceY, sourceWidth, sourceHeight;
        public final boolean virtual;
        public final float column, row, columns, rows, clipLeft, clipTop, clipRight, clipBottom;

        ImagePlacement(TerminalGraphics.Placement p) {
            image = p.image;
            imageId = p.imageId;
            placementId = p.placementId;
            z = p.z;
            sourceX = p.sourceX; sourceY = p.sourceY;
            sourceWidth = p.sourceWidth; sourceHeight = p.sourceHeight;
            virtual = p.virtual;
            column = p.column; row = p.row; columns = p.columns; rows = p.rows;
            clipLeft = p.clipLeft; clipTop = p.clipTop; clipRight = p.clipRight; clipBottom = p.clipBottom;
        }

        public float virtualScale(float cellWidth, float cellHeight) {
            return Math.min(columns * cellWidth / sourceWidth, rows * cellHeight / sourceHeight);
        }
    }
}
