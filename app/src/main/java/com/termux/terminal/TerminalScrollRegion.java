package com.termux.terminal;

import java.util.BitSet;

/** Presentation-only cell reconciliation; no clock, Canvas or PTY packet boundaries. */
final class TerminalScrollRegion {
    BitSet[] written, stationary;
    private BitSet[] previousStationary;
    TerminalRow[] moving;
    private TerminalRow[] transported, viewport;
    private int[] origins;
    private int scrollRows;
    int left, top, right, bottom, direction;
    private boolean pendingRepaints;

    void reset() {
        written = stationary = previousStationary = null;
        moving = transported = viewport = null;
        origins = null;
        pendingRepaints = false;
    }

    void begin(TerminalEmulator emulator, int l, int t, int r, int b, int rows) {
        left = l; top = t; right = r; bottom = b; direction = Integer.signum(rows);
        if (moving == null) {
            written = new BitSet[emulator.mRows];
            stationary = new BitSet[emulator.mRows];
            previousStationary = new BitSet[emulator.mRows];
            moving = new TerminalRow[emulator.mRows];
            transported = new TerminalRow[emulator.mRows];
            viewport = new TerminalRow[emulator.mRows];
            origins = new int[emulator.mRows];
            for (int row = top; row < bottom; row++) {
                written[row] = new BitSet(emulator.mColumns);
                stationary[row] = new BitSet(emulator.mColumns);
                moving[row] = liveRow(emulator, row).snapshot();
            }
        }
        for (int row = top; row < bottom; row++) {
            viewport[row] = liveRow(emulator, row).snapshot();
            previousStationary[row] = (BitSet) stationary[row].clone();
        }
    }

    void transport(int rows) {
        scrollRows = rows;
        for (int row = top; row < bottom; row++) {
            int source = row - rows;
            origins[row] = source >= top && source < bottom ? source : -1;
            transported[row] = origins[row] < 0 ? null : moving[source];
            written[row].clear();
            stationary[row].clear();
        }
    }

    private static TerminalRow liveRow(TerminalEmulator emulator, int row) {
        TerminalBuffer screen = emulator.getScreen();
        return screen.mLines[screen.externalToInternalRow(row)];
    }

    void scrollComplete(TerminalEmulator emulator) {
        if (moving == null) return;
        for (int row = top; row < bottom; row++) {
            if (transported[row] == null) transported[row] = liveRow(emulator, row).snapshot();
        }
        pendingRepaints = true;
    }

    void cellsChanged(TerminalEmulator emulator, int l, int t, int r, int b) {
        if (moving == null) return;
        l = Math.max(left, l);
        r = Math.min(right, r);
        if (r <= l) return;
        for (int row = Math.max(top, t); row < Math.min(bottom, b); row++) {
            TerminalRow current = liveRow(emulator, row), base = transported[row];
            written[row].set(Math.min(base.glyphStart(l), current.glyphStart(l)),
                    Math.max(base.glyphEnd(r), current.glyphEnd(r)));
            pendingRepaints = true;
        }
    }

    void reconcileRepaints(TerminalEmulator emulator) {
        if (moving == null || !pendingRepaints) return;
        pendingRepaints = false;
        // Rebuild from one immutable post-transport baseline. Rendering a partial
        // redraw must not change the evidence used to classify subsequent writes.
        for (int row = top; row < bottom; row++) stationary[row].clear();
        pinRestoredRows(emulator);
        for (int row = top; row < bottom; row++) pinStyledRestoration(emulator, row);
        for (int row = top; row < bottom; row++) pinKnownRestoration(emulator, row);
        for (int row = top; row < bottom; row++) {
            moving[row] = transported[row];
            BitSet updates = (BitSet) written[row].clone();
            updates.andNot(stationary[row]);
            if (updates.isEmpty()) continue;
            TerminalRow line = transported[row].snapshot(), current = liveRow(emulator, row);
            for (int start = updates.nextSetBit(0); start >= 0;) {
                int end = updates.nextClearBit(start);
                line.copyCells(current, start, end);
                start = updates.nextSetBit(end);
            }
            moving[row] = line;
        }
    }

    TerminalRow[] presentationRows(TerminalEmulator emulator) {
        reconcileRepaints(emulator);
        return moving;
    }

    private void pinKnownRestoration(TerminalEmulator emulator, int row) {
        int source = origins[row];
        if (source < 0) return;
        BitSet paired = (BitSet) written[row].clone();
        paired.and(written[source]);
        TerminalRow target = liveRow(emulator, row);
        for (int start = paired.nextSetBit(left); start >= 0 && start < right;) {
            int end = Math.min(right, paired.nextClearBit(start));
            boolean repaired = !sameCells(target, viewport[source], start, end);
            boolean established = previousStationary[source].nextClearBit(start) >= end;
            // Coincidentally restoring one ordinary letter is not evidence of a panel.
            // Partial writes can retain an established span, never discover a new one.
            if (repaired && established) stationary[source].set(start, end);
            start = paired.nextSetBit(end);
        }
    }

    private void pinStyledRestoration(TerminalEmulator emulator, int row) {
        TerminalRow before = viewport[row], current = liveRow(emulator, row);
        int displacedRow = row + scrollRows;
        for (int start = left; start < right;) {
            long style = before.mStyle[start];
            int end = start + 1;
            while (end < right && before.mStyle[end] == style) end++;
            boolean restored = written[row].nextClearBit(start) >= end;
            for (int c = start; c < end && restored; c++) {
                restored = current.mStyle[c] == style && transported[row].mStyle[c] != style;
            }
            if (restored && displacedRow >= top && displacedRow < bottom) {
                // Restoring ordinary text over a displaced panel is not itself a panel.
                // Its own displaced copy must also be repaired, or have left the region.
                restored = written[displacedRow].nextClearBit(start) >= end
                        && !sameCells(liveRow(emulator, displacedRow), before, start, end);
            }
            // A complete styled span can be restored with new text (a counter),
            // including at the departing edge where its displaced copy is offscreen.
            if (restored) stationary[row].set(start, end);
            start = end;
        }
    }

    private void pinRestoredRows(TerminalEmulator emulator) {
        // A restored band may contain identical adjacent rows. At least one row
        // must actually have been displaced; repeated history lines are not a panel.
        int start = top;
        while (start < bottom) {
            int end = start;
            boolean displaced = false;
            while (end < bottom && written[end].nextClearBit(left) >= right
                    && sameCells(liveRow(emulator, end), viewport[end], left, right)) {
                displaced |= !sameCells(transported[end], viewport[end], left, right);
                end++;
            }
            if (displaced) {
                for (int row = start; row < end; row++) stationary[row].set(left, right);
            }
            start = Math.max(start + 1, end);
        }
    }

    private static boolean sameCells(TerminalRow a, TerminalRow b, int start, int end) {
        for (int c = start; c < end; c++) if (!a.sameCell(b, c)) return false;
        return true;
    }
}
