package com.termux.terminal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Canvas renderer owned by MagicDesk; terminal parsing remains in TerminalEmulator. */
public final class MagicDeskTerminalRenderer {
    private static final int SELECTION_COLOR = 0x995C7CFA;

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint();
    private final float mCellWidth;
    private final float mCellHeight;
    private final float mBaseline;

    public MagicDeskTerminalRenderer(final float scaledDensity) {
        mTextPaint.setTypeface(Typeface.MONOSPACE);
        mTextPaint.setTextSize(14.0f * scaledDensity);
        final Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
        mCellWidth = (float) Math.ceil(mTextPaint.measureText("M"));
        mCellHeight = (float) Math.ceil(
                metrics.descent - metrics.ascent + metrics.leading);
        mBaseline = -metrics.ascent;
    }

    public float cellWidth() {
        return mCellWidth;
    }

    public float cellHeight() {
        return mCellHeight;
    }

    public void draw(
            final Canvas canvas,
            final TerminalEmulator emulator,
            final int topRow,
            final int visibleRows,
            final int selectionStartColumn,
            final int selectionStartRow,
            final int selectionEndColumn,
            final int selectionEndRow,
            final boolean focused) {
        final int[] colors = emulator.mColors.mCurrentColors;
        final int defaultBackground = colors[TextStyle.COLOR_INDEX_BACKGROUND];
        canvas.drawColor(defaultBackground);
        final TerminalBuffer screen = emulator.getScreen();
        for (int viewportRow = 0; viewportRow < visibleRows; viewportRow++) {
            final int externalRow = topRow + viewportRow;
            if (externalRow < -screen.getActiveTranscriptRows()
                    || externalRow >= emulator.mRows) {
                continue;
            }
            drawRow(
                    canvas,
                    emulator,
                    screen,
                    externalRow,
                    viewportRow,
                    selectionStartColumn,
                    selectionStartRow,
                    selectionEndColumn,
                    selectionEndRow,
                    focused);
        }
    }

    private void drawRow(
            final Canvas canvas,
            final TerminalEmulator emulator,
            final TerminalBuffer screen,
            final int externalRow,
            final int viewportRow,
            final int selectionStartColumn,
            final int selectionStartRow,
            final int selectionEndColumn,
            final int selectionEndRow,
            final boolean focused) {
        final TerminalRow row = screen.mLines[
                screen.externalToInternalRow(externalRow)];
        if (row == null) {
            return;
        }
        int previousCharacterStart = -1;
        for (int column = 0; column < emulator.mColumns; column++) {
            final int characterStart = row.findStartOfColumn(column);
            if (characterStart == previousCharacterStart) {
                continue;
            }
            previousCharacterStart = characterStart;
            final int codePoint = Character.codePointAt(
                    row.mText, characterStart, row.getSpaceUsed());
            final int displayWidth = Math.max(1, WcWidth.width(codePoint));
            final int endColumn = Math.min(
                    emulator.mColumns, column + displayWidth);
            final int characterEnd = row.findStartOfColumn(endColumn);
            final long style = row.mStyle[column];
            drawCell(
                    canvas,
                    emulator,
                    row.mText,
                    characterStart,
                    characterEnd,
                    column,
                    displayWidth,
                    externalRow,
                    viewportRow,
                    style,
                    isSelected(
                            column,
                            externalRow,
                            selectionStartColumn,
                            selectionStartRow,
                            selectionEndColumn,
                            selectionEndRow),
                    focused
                            && externalRow == emulator.getCursorRow()
                            && column == emulator.getCursorCol()
                            && emulator.shouldCursorBeVisible());
        }
    }

    private void drawCell(
            final Canvas canvas,
            final TerminalEmulator emulator,
            final char[] text,
            final int textStart,
            final int textEnd,
            final int column,
            final int displayWidth,
            final int externalRow,
            final int viewportRow,
            final long style,
            final boolean selected,
            final boolean cursor) {
        int foreground = resolveColor(
                emulator, TextStyle.decodeForeColor(style));
        int background = resolveColor(
                emulator, TextStyle.decodeBackColor(style));
        final int effects = TextStyle.decodeEffect(style);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0
                ^ emulator.isReverseVideo()) {
            final int swap = foreground;
            foreground = background;
            background = swap;
        }
        final float left = column * mCellWidth;
        final float top = viewportRow * mCellHeight;
        final float right = left + displayWidth * mCellWidth;
        final float bottom = top + mCellHeight;
        mFillPaint.setColor(background);
        canvas.drawRect(left, top, right, bottom, mFillPaint);
        if (selected) {
            mFillPaint.setColor(SELECTION_COLOR);
            canvas.drawRect(left, top, right, bottom, mFillPaint);
        }
        if (cursor) {
            drawCursor(canvas, emulator, left, top, right, bottom);
            if (emulator.getCursorStyle()
                    == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                foreground = background;
            }
        }
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0
                || textEnd <= textStart) {
            return;
        }
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) {
            foreground = dim(foreground);
        }
        mTextPaint.setColor(foreground);
        mTextPaint.setFakeBoldText(
                (effects & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0);
        mTextPaint.setTextSkewX(
                (effects & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0
                        ? -0.2f : 0.0f);
        mTextPaint.setUnderlineText(
                (effects & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0);
        mTextPaint.setStrikeThruText(
                (effects & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0);
        canvas.drawText(
                text,
                textStart,
                textEnd - textStart,
                left,
                top + mBaseline,
                mTextPaint);
    }

    private void drawCursor(
            final Canvas canvas,
            final TerminalEmulator emulator,
            final float left,
            final float top,
            final float right,
            final float bottom) {
        mFillPaint.setColor(resolveColor(
                emulator, TextStyle.COLOR_INDEX_CURSOR));
        final int style = emulator.getCursorStyle();
        if (style == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
            canvas.drawRect(left, bottom - 2.0f, right, bottom, mFillPaint);
        } else if (style == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
            canvas.drawRect(left, top, left + 2.0f, bottom, mFillPaint);
        } else {
            canvas.drawRect(new RectF(left, top, right, bottom), mFillPaint);
        }
    }

    private static int resolveColor(
            final TerminalEmulator emulator, final int encoded) {
        if ((encoded & 0xFF000000) == 0xFF000000) {
            return encoded;
        }
        final int[] colors = emulator.mColors.mCurrentColors;
        return encoded >= 0 && encoded < colors.length
                ? colors[encoded] : colors[TextStyle.COLOR_INDEX_FOREGROUND];
    }

    private static int dim(final int color) {
        return (color & 0xFF000000)
                | (((color >>> 16) & 0xFF) / 2 << 16)
                | (((color >>> 8) & 0xFF) / 2 << 8)
                | ((color & 0xFF) / 2);
    }

    private static boolean isSelected(
            final int column,
            final int row,
            final int startColumn,
            final int startRow,
            final int endColumn,
            final int endRow) {
        if (startRow == Integer.MIN_VALUE || endRow == Integer.MIN_VALUE) {
            return false;
        }
        final long cell = position(row, column);
        final long first = Math.min(
                position(startRow, startColumn),
                position(endRow, endColumn));
        final long last = Math.max(
                position(startRow, startColumn),
                position(endRow, endColumn));
        return cell >= first && cell <= last;
    }

    private static long position(final int row, final int column) {
        return ((long) row << 32) | (column & 0xFFFFFFFFL);
    }
}
