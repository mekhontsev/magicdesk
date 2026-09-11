package com.termux.terminal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

/** Canvas renderer owned by MagicDesk; terminal parsing remains in TerminalEmulator. */
public final class MagicDeskTerminalRenderer {
    private static final int SELECTION_COLOR = 0x995C7CFA;

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint();
    private final Typeface[] mFaces = new Typeface[4];
    private final TerminalCellGeometry mGeometry = new TerminalCellGeometry();
    private final Rect mGlyphBounds = new Rect();
    private final float mCellWidth;
    private final float mCellHeight;
    private final float mBaseline;

    public MagicDeskTerminalRenderer(final Typeface family, final float textSizePixels) {
        mTextPaint.setTextSize(textSizePixels);
        float ascent = 0, descent = 0, leading = 0, width = 0;
        // All four real faces share one grid, including the PTY's pixel dimensions.
        for (int style = 0; style < mFaces.length; style++) {
            mFaces[style] = Typeface.create(family, style);
            mTextPaint.setTypeface(mFaces[style]);
            final Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
            ascent = Math.min(ascent, metrics.ascent);
            descent = Math.max(descent, metrics.descent);
            leading = Math.max(leading, metrics.leading);
            width = Math.max(width, mTextPaint.measureText("M"));
        }
        mCellWidth = (float) Math.ceil(width);
        mBaseline = (float) Math.ceil(-ascent);
        mCellHeight = mBaseline + (float) Math.ceil(descent + leading);
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
                    row.getHyperlink(column) != null,
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
            final boolean hyperlink,
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
        final int face = ((effects & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0 ? Typeface.BOLD : 0)
                | ((effects & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 ? Typeface.ITALIC : 0);
        mTextPaint.setTypeface(mFaces[face]);
        final int codePoint = Character.codePointAt(text, textStart, textEnd);
        final int saved = canvas.save();
        canvas.clipRect(left, top, right, bottom);
        // Combining clusters remain font-shaped; geometry only replaces a single base glyph.
        if (textEnd - textStart != Character.charCount(codePoint)
                || !mGeometry.draw(canvas, codePoint, left, top, right - left, mCellHeight, foreground)) {
            drawText(canvas, text, textStart, textEnd, codePoint, left, top, right - left);
        }
        mFillPaint.setColor(foreground);
        final float stroke = Math.max(1, Math.round(mCellHeight / 24));
        if (hyperlink || (effects & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) {
            final float y = Math.min(bottom - stroke, top + mBaseline + stroke);
            canvas.drawRect(left, y, right, y + stroke, mFillPaint);
        }
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0) {
            final float y = top + Math.round(mBaseline * 0.65f);
            canvas.drawRect(left, y, right, y + stroke, mFillPaint);
        }
        canvas.restoreToCount(saved);
    }

    private void drawText(final Canvas canvas, final char[] text, final int start, final int end,
            final int codePoint, final float left, final float top, final float width) {
        final int saved = canvas.save();
        if (Character.getType(codePoint) == Character.PRIVATE_USE) {
            // Icon ink must fit its logical cell, never consume an adjacent space or column.
            mTextPaint.getTextBounds(text, start, end - start, mGlyphBounds);
            if (!mGlyphBounds.isEmpty()) {
                final float scale = Math.min(1, Math.min(width / mGlyphBounds.width(),
                        mCellHeight / mGlyphBounds.height()));
                canvas.translate(left + (width - mGlyphBounds.width() * scale) / 2,
                        top + (mCellHeight - mGlyphBounds.height() * scale) / 2);
                canvas.scale(scale, scale);
                canvas.drawText(text, start, end - start,
                        -mGlyphBounds.left, -mGlyphBounds.top, mTextPaint);
                canvas.restoreToCount(saved);
                return;
            }
        }
        final float advance = mTextPaint.measureText(text, start, end - start);
        canvas.translate(left, top + mBaseline);
        if (advance > width) {
            canvas.scale(width / advance, 1);
        }
        canvas.drawText(text, start, end - start, 0, 0, mTextPaint);
        canvas.restoreToCount(saved);
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
            canvas.drawRect(left, top, right, bottom, mFillPaint);
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
