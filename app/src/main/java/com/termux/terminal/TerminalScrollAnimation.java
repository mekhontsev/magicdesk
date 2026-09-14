package com.termux.terminal;

import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.Path;

import java.util.ArrayDeque;

/** Bounded presentation of semantic region scrolling, independent of the terminal application. */
public final class TerminalScrollAnimation {
    private static final int NO_SELECTION = Integer.MIN_VALUE;
    private final ArrayDeque<Picture> outgoing = new ArrayDeque<>();
    private final TerminalScrollMotion motion = new TerminalScrollMotion();
    private final Path repaintClip = new Path();
    private final TerminalScrollRegion region = new TerminalScrollRegion();
    private float cellWidth, cellHeight;
    public void reset() {
        clearPresentation();
        motion.reset();
    }

    private void clearPresentation() {
        outgoing.clear();
        region.reset();
        repaintClip.rewind();
    }

    public void input(int rows, boolean continuous, double frameMillis, long now) {
        motion.input(Integer.signum(rows), continuous, frameMillis, now);
    }

    public void endInput(long now) { motion.endInput(now); }

    /** Called on the UI thread before the emulator performs the edit. No PTY read boundaries are assumed. */
    public void beforeScroll(TerminalEmulator emulator, MagicDeskTerminalRenderer renderer,
            int l, int t, int r, int b, int rows, double frameMillis, long now) {
        advance(now);
        region.reconcileRepaints(emulator);
        int count = Math.abs(rows);
        if (l < 0 || t < 0 || r > emulator.mColumns || b > emulator.mRows
                || r <= l || b <= t || count == 0 || count >= b - t) {
            reset();
            return;
        }
        int nextDirection = Integer.signum(rows);
        if (region.moving != null && (region.left != l || region.top != t || region.right != r || region.bottom != b || region.direction != nextDirection
                || cellWidth != renderer.cellWidth() || cellHeight != renderer.cellHeight())) reset();
        cellWidth = renderer.cellWidth();
        cellHeight = renderer.cellHeight();
        region.begin(emulator, l, t, r, b, rows);
        final TerminalFrame before = TerminalFrame.capture(emulator, 0, emulator.mRows).withRows(region.moving);
        // Record only departing rows, not full-screen bitmaps or recursively composed frames.
        // Pictures retain existing image rasters, and never copy their pixels.
        for (int i = 0; i < count; i++) {
            int row = region.direction < 0 ? region.top + i : region.bottom - 1 - i;
            Picture picture = new Picture();
            Canvas recording = picture.beginRecording(Math.round((region.right - region.left) * cellWidth), Math.round(cellHeight));
            // A Picture's declared size is not a playback clip, including for drawColor.
            recording.clipRect(0, 0, (region.right - region.left) * cellWidth, cellHeight);
            recording.translate(-region.left * cellWidth, 0);
            renderer.drawScrolling(recording, before, row, 1);
            picture.endRecording();
            if (region.direction < 0) outgoing.addLast(picture);
            else outgoing.addFirst(picture);
        }
        region.transport(rows);
        motion.add(count * cellHeight, (region.bottom - region.top) * cellHeight, frameMillis, now);
        trim();
    }

    public void scrollComplete(TerminalEmulator emulator) { region.scrollComplete(emulator); }

    public void cellsChanged(TerminalEmulator emulator, int l, int t, int r, int b) {
        region.cellsChanged(emulator, l, t, r, b);
    }

    public boolean advance(long now) {
        if (outgoing.isEmpty()) return false;
        if (!motion.advance(now)) clearPresentation();
        else trim();
        return !outgoing.isEmpty();
    }

    private void trim() {
        int keep = (int) Math.ceil(motion.remainingPixels() / cellHeight);
        while (outgoing.size() > keep) {
            if (region.direction < 0) outgoing.removeFirst();
            else outgoing.removeLast();
        }
    }

    /** Caller advances the clock once per frame; selection and local-history views use the ordinary renderer. */
    public void draw(Canvas canvas, TerminalEmulator emulator, MagicDeskTerminalRenderer renderer, boolean focused) {
        region.reconcileRepaints(emulator);
        final TerminalFrame frame = TerminalFrame.capture(emulator, 0, emulator.mRows);
        final TerminalFrame movingFrame = frame.withRows(region.presentationRows(emulator));
        // Recorded row clips and live glyphs must meet on the same physical pixel boundary.
        float displacement = Math.round(motion.remainingPixels());
        float x = region.left * cellWidth, y = region.top * cellHeight;
        float endX = region.right * cellWidth, endY = region.bottom * cellHeight;
        int saved = canvas.save();
        canvas.clipOutRect(x, y, endX, endY);
        renderer.draw(canvas, frame, 0, emulator.mRows, 0, NO_SELECTION, NO_SELECTION, 0, 0, focused);
        canvas.restoreToCount(saved);

        saved = canvas.save();
        canvas.clipRect(x, y, endX, endY);
        int shifted = canvas.save();
        canvas.translate(0, -region.direction * displacement);
        // The terminal cursor describes the final state, not the moving text.
        renderer.drawScrolling(canvas, movingFrame, 0, emulator.mRows);
        canvas.restoreToCount(shifted);
        canvas.translate(x, region.direction < 0
                ? y + displacement - outgoing.size() * cellHeight : endY - displacement);
        for (Picture picture : outgoing) {
            canvas.drawPicture(picture);
            canvas.translate(0, cellHeight);
        }
        canvas.restoreToCount(saved);

        // Only actual in-place changes are fixed. No swept-path mask can cut moving
        // glyphs or make another part of the same line use a different scroll offset.
        repaintClip.rewind();
        for (int row = region.top; row < region.bottom; row++) {
            java.util.BitSet dirty = region.stationary[row];
            float rowY = row * cellHeight;
            for (int column = dirty.nextSetBit(0); column >= 0;) {
                int end = dirty.nextClearBit(column);
                repaintClip.addRect(column * cellWidth, rowY,
                        end * cellWidth, rowY + cellHeight, Path.Direction.CW);
                column = dirty.nextSetBit(end);
            }
        }
        if (!repaintClip.isEmpty()) {
            saved = canvas.save();
            canvas.clipRect(x, y, endX, endY);
            canvas.clipPath(repaintClip);
            renderer.draw(canvas, frame, 0, emulator.mRows, 0, NO_SELECTION, NO_SELECTION, 0, 0, focused);
            canvas.restoreToCount(saved);
        }
    }
}
