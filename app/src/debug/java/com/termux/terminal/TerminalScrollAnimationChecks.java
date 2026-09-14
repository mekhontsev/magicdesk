package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;

import java.nio.charset.StandardCharsets;

/** Pixel checks on Android's actual Picture/Canvas path, with a deterministic frame clock. */
final class TerminalScrollAnimationChecks {
    private int checks;

    static int verify(Typeface font) {
        TerminalScrollAnimationChecks test = new TerminalScrollAnimationChecks();
        for (int sign : new int[] {-1, 1}) {
            test.region(font, sign, 0, 7);
            test.region(font, sign, 1, 4);
            test.region(font, sign, 4, 7);
            test.continuous(font, sign);
            test.repaintDuringScroll(font, sign, 0, 7);
            test.repaintDuringScroll(font, sign, 2, 7);
            test.freshRowsStaySmooth(font, sign);
            test.repaintKeepsWholeTextInPhase(font, sign);
            test.repaintOverIncomingBlanks(font, sign);
            test.updatedTextKeepsScrollOffset(font, sign);
            test.historyRedrawKeepsScrollOffset(font, sign);
        }
        test.flingPastViewport(font);
        test.bottomRepaintAfterOutput(font);
        test.bottomBandAfterOutput(font);
        test.resetAndRepaint(font);
        return test.checks;
    }

    private void updatedTextKeepsScrollOffset(Typeface font, int sign) {
        try (Fixture control = new Fixture(font); Fixture subject = new Fixture(font)) {
            control.fill();
            subject.fill();
            for (int source : new int[]{3, 4}) {
                append(control.emulator, "\033[" + (source + 1) + ";1H\033[0mAxAxAxAx");
            }
            control.edit(sign, 0, 7, 2);
            subject.edit(sign, 0, 7, 2);
            for (int source : new int[]{3, 4}) {
                String update = "\033[" + (source + sign * 2 + 1) + ";1H\033[0mAxAxAxAx";
                for (int i = 0; i < update.length(); i++) {
                    append(subject.emulator, update.substring(i, i + 1));
                    subject.frame().recycle();
                }
            }
            compareMotion(control, subject, "updated text arrived before moving rows sign=" + sign);
        }
    }

    private void historyRedrawKeepsScrollOffset(Typeface font, int sign) {
        try (Fixture control = new Fixture(font); Fixture subject = new Fixture(font)) {
            for (int row = 0; row < 8; row++) {
                String text = "\033[" + (row + 1) + ";1HA" + row + "A" + row + "A" + row + "A" + row;
                append(control.emulator, text);
                append(subject.emulator, text);
            }
            control.edit(sign, 0, 7, 2);
            subject.edit(sign, 0, 7, 2);
            for (int row = 0; row < 7; row++) {
                String update = "\033[" + (row + 1) + ";1H\033[K"
                        + control.emulator.getSelectedText(0, row, 7, row);
                for (int i = 0; i < update.length(); i++) {
                    append(subject.emulator, update.substring(i, i + 1));
                    subject.frame().recycle();
                }
            }
            compareMotion(control, subject, "redraw split moving history sign=" + sign);
        }
    }

    private void compareMotion(Fixture control, Fixture subject, String message) {
        for (int time : new int[]{0, 7, 21, 40, 80, 120, 240, 400}) {
            control.now = subject.now = time;
            Bitmap expected = control.frame(), actual = subject.frame();
            try {
                compare(expected, actual, 0, 0, 8 * subject.cw, 8 * subject.ch, message + " time=" + time);
            } finally {
                expected.recycle();
                actual.recycle();
            }
        }
    }

    private void region(Typeface font, int sign, int top, int bottom) {
        try (Fixture f = new Fixture(font)) {
            f.fill();
            Bitmap before = f.frame();
            f.edit(sign, top, bottom, 2);
            Bitmap after = f.committed();
            Bitmap start = f.frame();
            compare(before, start, 0, top * f.ch, f.cw * 8, bottom * f.ch, "initial frame shifted");
            start.recycle();
            f.now = 40;
            Bitmap middle = f.frame();
            compare(after, middle, 0, 0, f.cw * 8, top * f.ch, "upper neighbor moved");
            compare(after, middle, 0, bottom * f.ch, f.cw * 8, 8 * f.ch, "status/lower neighbor moved");
            float remaining = (float) (2 * f.ch * (1 + 40 / 32.0) * Math.exp(-40 / 32.0));
            int matched = 0;
            for (int y = top * f.ch + 2; y < bottom * f.ch - 2; y++) {
                float sourceY = y + sign * remaining;
                // Solid row backgrounds avoid glyph rasterization/rounding differences at fractional coordinates.
                if (sourceY <= top * f.ch + 2 || sourceY >= bottom * f.ch - 2
                        || sourceY % f.ch < 2 || sourceY % f.ch > f.ch - 2) continue;
                check(middle.getPixel(7 * f.cw, y) == after.getPixel(7 * f.cw, (int) sourceY),
                        "content does not follow fractional region offset");
                matched++;
            }
            check(matched > 0, "no intermediate pixels verified");
            middle.recycle();
            f.now = 400;
            Bitmap end = f.frame();
            compare(after, end, 0, 0, 8 * f.cw, 8 * f.ch, "final frame differs from committed cells");
            check(!f.animation.advance(401), "idle animation still active");
            before.recycle();
            after.recycle();
            end.recycle();
        }
    }

    private void continuous(Typeface font, int sign) {
        try (Fixture f = new Fixture(font)) {
            f.fill();
            f.edit(sign, 1, 7, 1);
            f.now = 30;
            Bitmap beforeNext = f.frame();
            f.edit(sign, 1, 7, 1);
            Bitmap afterNext = f.frame();
            compare(beforeNext, afterNext, 0, 0, 8 * f.cw, 8 * f.ch, "successive scroll jumped");
            beforeNext.recycle();
            afterNext.recycle();
            // More input than one viewport must stay bounded and drain after the last edit.
            for (int i = 0; i < 80; i++) {
                f.now++;
                f.edit(sign, 1, 7, 1);
                Bitmap frame = f.frame();
                Bitmap state = f.committed();
                compare(state, frame, 0, 7 * f.ch, 8 * f.cw, 8 * f.ch, "rapid scroll moved status");
                frame.recycle();
                state.recycle();
            }
            f.now += 400;
            check(!f.animation.advance(f.now), "rapid scroll left an animation backlog");
            f.edit(-sign, 1, 7, 1);
            check(f.animation.advance(f.now), "reversed scroll not animated");
            f.now += 400;
            check(!f.animation.advance(f.now), "reversed scroll did not settle");
        }
    }

    private void resetAndRepaint(Typeface font) {
        try (Fixture f = new Fixture(font)) {
            f.fill();
            f.edit(-1, 0, 7, 1);
            append(f.emulator, "\033[2J");
            check(!f.animation.advance(f.now), "erase retained departing rows");
            f.fill();
            f.edit(1, 0, 7, 1);
            append(f.emulator, "\033[?1049h");
            check(!f.animation.advance(f.now), "buffer switch retained departing rows");
            append(f.emulator, "\033[HREPAINT\033[3;1HUPDATE");
            check(!f.animation.advance(f.now), "repaint invented a scroll");
            f.edit(-1, 0, 7, 1);
            f.emulator.resize(10, 10, f.cw, f.ch);
            check(!f.animation.advance(f.now), "resize retained old geometry");
        }
    }

    private void repaintDuringScroll(Typeface font, int sign, int top, int bottom) {
        try (Fixture f = new Fixture(font)) {
            f.fill();
            String marker = "\033[" + (top + 1) + ";6H\033[48;2;17;213;91m";
            append(f.emulator, marker + "OLD\033[0m");
            // Each edit repaints a viewport-local fragment and repairs its displaced copy.
            // This is ordinary terminal output, with no semantic hint identifying the fragment.
            for (int i = 0; i < 5; i++) {
                f.edit(sign, top, bottom, 1);
                int incoming = sign > 0 ? top : bottom - 1;
                append(f.emulator, "\033[" + (incoming + 1) + ";1H\033[0mnew     ");
                append(f.emulator, marker + "NEW\033[0m");
                if (sign > 0) append(f.emulator, "\033[" + (top + 2) + ";6H   ");
            }
            Bitmap committed = f.committed();
            for (int time : new int[] {0, 7, 21, 40, 80, 120, 240, 400}) {
                f.now = time;
                Bitmap frame = f.frame();
                compare(committed, frame, 5 * f.cw, top * f.ch, 8 * f.cw, (top + 1) * f.ch,
                        "repainted fragment moved sign=" + sign + " top=" + top + " time=" + time);
                for (int y = (top + 1) * f.ch; y < bottom * f.ch; y++)
                    for (int x = 5 * f.cw; x < 8 * f.cw; x++)
                        check(frame.getPixel(x, y) != 0xff11d55b, "intermediate fragment copied into scrolling rows");
                compare(committed, frame, 0, 0, 8 * f.cw, top * f.ch, "repaint moved upper neighbor");
                compare(committed, frame, 0, bottom * f.ch, 8 * f.cw, 8 * f.ch, "repaint moved status");
                frame.recycle();
            }
            committed.recycle();
        }
    }

    private void freshRowsStaySmooth(Typeface font, int sign) {
        try (Fixture f = new Fixture(font)) {
            f.fill();
            f.edit(sign, 0, 7, 2);
            int incoming = sign > 0 ? 0 : 5;
            append(f.emulator, "\033[" + (incoming + 1) + ";1H\033[K"
                    + "\033[" + (incoming + 2) + ";1H\033[K");
            append(f.emulator, "\033[" + (incoming + 1) + ";1H\033[48;2;97;151;211m        "
                    + "\033[" + (incoming + 2) + ";1H        \033[0m");
            Bitmap start = f.frame();
            int freshPixels = 0;
            for (int y = 0; y < 7 * f.ch; y++) if (start.getPixel(7 * f.cw, y) == 0xff6197d3) freshPixels++;
            check(freshPixels == 0, "incoming text was settled instead of animated");
            start.recycle();
            f.now = 40;
            Bitmap middle = f.frame();
            for (int y = 0; y < 7 * f.ch; y++) if (middle.getPixel(7 * f.cw, y) == 0xff6197d3) freshPixels++;
            check(freshPixels > 0 && freshPixels < 2 * f.ch, "incoming text did not move fractionally");
            middle.recycle();
        }
    }

    private void repaintKeepsWholeTextInPhase(Typeface font, int sign) {
        try (Fixture control = new Fixture(font); Fixture subject = new Fixture(font)) {
            for (int row = 0; row < 8; row++) {
                String text = "\033[" + (row + 1) + ";1HROW" + String.valueOf(row).repeat(5);
                append(control.emulator, text);
                append(subject.emulator, text);
            }
            append(subject.emulator, "\033[1;6H\033[48;2;17;213;91mOLD\033[0m");
            for (int i = 0; i < 5; i++) {
                control.edit(sign, 0, 7, 1);
                subject.edit(sign, 0, 7, 1);
                String incoming = "\033[" + (sign > 0 ? 1 : 7) + ";1HNEW" + String.valueOf(i).repeat(5);
                append(control.emulator, incoming);
                append(subject.emulator, incoming);
                append(subject.emulator, "\033[1;6H\033[48;2;17;213;91m" + i + "XX\033[0m");
                // A TUI can redraw the next row in either direction. An unchanged
                // source row is not evidence that the fixed marker belongs to it.
                append(subject.emulator, "\033[2;6H"
                        + control.emulator.getSelectedText(5, 1, 7, 1));
                // A redundant repaint in the body must not pin this fragment to the final grid.
                append(subject.emulator, "\033[4;6H" + control.emulator.getSelectedText(5, 3, 7, 3));
            }
            for (int time : new int[] {0, 7, 21, 40, 80, 120, 240, 400}) {
                control.now = subject.now = time;
                Bitmap expected = control.frame(), actual = subject.frame();
                compare(expected, actual, 0, 0, 5 * subject.cw, subject.ch, "prefix changed phase");
                compare(expected, actual, 0, subject.ch, 8 * subject.cw, 8 * subject.ch,
                        "body fragment moved independently of its row");
                expected.recycle();
                actual.recycle();
            }
        }
    }

    private void repaintOverIncomingBlanks(Typeface font, int sign) {
        try (Fixture control = new Fixture(font); Fixture subject = new Fixture(font)) {
            for (int row = 0; row < 8; row++) {
                String text = "\033[" + (row + 1) + ";1HROW" + row;
                append(control.emulator, text);
                append(subject.emulator, text);
            }
            append(subject.emulator, "\033[1;6H\033[48;2;17;213;91mOLD\033[0m");
            for (int i = 0; i < 5; i++) {
                control.edit(sign, 0, 7, 1);
                subject.edit(sign, 0, 7, 1);
                String incoming = "\033[" + (sign > 0 ? 1 : 7) + ";1HNEW" + i;
                append(control.emulator, incoming);
                append(subject.emulator, incoming);
                append(subject.emulator, "\033[1;6H\033[48;2;17;213;91m" + i + "XX\033[0m");
                if (sign > 0) append(subject.emulator, "\033[2;6H   ");
            }
            for (int time : new int[] {0, 7, 21, 40, 80, 120, 240, 400}) {
                control.now = subject.now = time;
                Bitmap expected = control.frame(), actual = subject.frame();
                compare(expected, actual, 0, subject.ch, 8 * subject.cw, 8 * subject.ch,
                        "repaint over incoming blanks entered moving text");
                Bitmap committed = subject.committed();
                compare(committed, actual, 5 * subject.cw, 0, 8 * subject.cw, subject.ch,
                        "repaint over blanks left its viewport position");
                committed.recycle();
                expected.recycle();
                actual.recycle();
            }
        }
    }

    private void flingPastViewport(Typeface font) {
        for (int top : new int[] {0, 2}) {
            try (Fixture control = new Fixture(font); Fixture subject = new Fixture(font)) {
                for (int row = 0; row < 8; row++) {
                    String text = "\033[" + (row + 1) + ";1HROW" + row;
                    append(control.emulator, text);
                    append(subject.emulator, text);
                }
                String markerPosition = "\033[" + (top + 1) + ";6H";
                append(subject.emulator, markerPosition + "\033[48;2;17;213;91mOLD\033[0m");
                // Many edits between frames carry short lines and their untouched blanks
                // across the entire viewport. Those blanks are no longer incoming cells.
                for (int i = 80; i >= 0; i--) {
                    control.now = subject.now += i % 5 == 0 ? 16 : 1;
                    control.edit(-1, top, 7, 1);
                    subject.edit(-1, top, 7, 1);
                    append(control.emulator, "\033[7;1HNEW" + i % 10);
                    append(subject.emulator, "\033[7;1HNEW" + i % 10);
                    String marker = String.format(java.util.Locale.ROOT, "%3d", i);
                    append(subject.emulator, markerPosition + "\033[48;2;17;213;91m" + marker + "\033[0m");
                    if (i % 5 == 0) {
                        // A repaint may restore the underlying cells before redrawing itself.
                        append(subject.emulator, markerPosition + "   " + markerPosition
                                + "\033[48;2;17;213;91m" + marker + "\033[0m");
                    }
                    compareFlingBody(control, subject, top, "rapid edit " + i);
                }
                // Extra wheel events at the boundary can redraw the indicator without scrolling.
                for (int i = 0; i < 24; i++) {
                    control.now = subject.now += 7;
                    append(subject.emulator, markerPosition + "   " + markerPosition
                            + "\033[48;2;17;213;91m  0\033[0m");
                    compareFlingBody(control, subject, top, "boundary repaint " + i);
                }
            }
        }
    }

    private void compareFlingBody(Fixture control, Fixture subject, int top, String stage) {
        Bitmap expected = control.frame(), actual = subject.frame(), committed = subject.committed();
        try {
            compare(expected, actual, 0, 0, 8 * subject.cw, top * subject.ch, "fling upper neighbor " + stage);
            compare(expected, actual, 0, top * subject.ch, 5 * subject.cw, (top + 1) * subject.ch,
                    "fling prefix phase " + stage);
            compare(expected, actual, 0, (top + 1) * subject.ch, 8 * subject.cw, 8 * subject.ch,
                    "fling body/repaint trail " + stage);
            compare(committed, actual, 5 * subject.cw, top * subject.ch, 8 * subject.cw, (top + 1) * subject.ch,
                    "fling fixed repaint " + stage);
        } finally {
            expected.recycle();
            actual.recycle();
            committed.recycle();
        }
    }

    private void bottomRepaintAfterOutput(Typeface font) {
        for (boolean footerFirst : new boolean[] {false, true}) {
            try (Fixture control = new Fixture(font); Fixture subject = new Fixture(font)) {
                for (int row = 0; row < 8; row++) {
                    String text = "\033[" + (row + 1) + ";1HROW" + row;
                    append(control.emulator, text);
                    append(subject.emulator, text);
                }
                String footer = "\033[7;6H\033[48;2;17;213;91mBOX\033[0m";
                append(subject.emulator, footer);
                for (int edit = 0; edit < 12; edit++) {
                    control.now = subject.now += 19;
                    control.edit(-1, 0, 7, 1);
                    subject.edit(-1, 0, 7, 1);
                    String incoming = "\033[7;1HNEW" + edit % 10;
                    append(control.emulator, incoming);
                    append(subject.emulator, incoming);
                    // Output can repair the displaced copy before repainting the footer.
                    // Neither update order nor PTY packet boundaries identify a fixed cell.
                    if (footerFirst) append(subject.emulator, footer);
                    append(subject.emulator, "\033[6;6H   ");
                    if (!footerFirst) append(subject.emulator, footer);
                    for (int dt : new int[] {0, 7, 16}) {
                        control.now = subject.now += dt;
                        Bitmap expected = control.frame(), actual = subject.frame();
                        Bitmap committed = subject.committed();
                        compare(expected, actual, 0, 0, 8 * subject.cw, 6 * subject.ch,
                                "output carried footer into text footerFirst=" + footerFirst);
                        compare(committed, actual, 5 * subject.cw, 6 * subject.ch,
                                8 * subject.cw, 7 * subject.ch, "output shifted fixed footer");
                        compare(expected, actual, 0, 7 * subject.ch, 8 * subject.cw, 8 * subject.ch,
                                "output shifted lower neighbor");
                        expected.recycle();
                        actual.recycle();
                        committed.recycle();
                    }
                }
            }
        }
    }

    private void bottomBandAfterOutput(Typeface font) {
        for (int height : new int[] {23, 36}) for (int count : new int[] {1, 5}) {
            try (Fixture control = new Fixture(font, height); Fixture subject = new Fixture(font, height)) {
                int bottom = height - 1, band = bottom - 3;
                for (int row = 0; row < height; row++) {
                    String text = "\033[" + (row + 1) + ";1HROW" + row % 10 + "....";
                    append(control.emulator, text);
                    append(subject.emulator, text);
                }
                for (int row = band; row < bottom; row++)
                    append(subject.emulator, "\033[" + (row + 1) + ";1H\033[48;2;17;213;91m"
                            + "FIXED" + row % 10 + "..\033[0m");
                for (int edit = 0; edit < 8; edit++) {
                    control.now = subject.now += 19;
                    control.edit(-1, 0, bottom, count);
                    subject.edit(-1, 0, bottom, count);
                    for (int row = bottom - count; row < bottom; row++) {
                        String text = "\033[" + (row + 1) + ";1H\033[KNEW" + edit + "....";
                        append(control.emulator, text);
                        append(subject.emulator, text);
                    }
                    // Repaint the lower UI band and its displaced copy in terminal row order.
                    for (int row = band - count; row < bottom; row++) {
                        String text = row < band ? control.emulator.getSelectedText(0, row, 7, row)
                                : "\033[48;2;17;213;91mFIXED" + row % 10 + "..\033[0m";
                        append(subject.emulator, "\033[" + (row + 1) + ";1H" + text);
                    }
                    for (int dt : new int[] {0, 7, 16}) {
                        control.now = subject.now += dt;
                        Bitmap actual = subject.frame(), committed = subject.committed();
                        compare(committed, actual, 0, band * subject.ch, 8 * subject.cw,
                                height * subject.ch, "fixed lower band moved height=" + height + " count=" + count);
                        for (int y = 0; y < band * subject.ch; y++) for (int x = 0; x < 8 * subject.cw; x++)
                            if (actual.getPixel(x, y) == 0xff11d55b)
                                throw new AssertionError("lower band carried into output height=" + height
                                        + " count=" + count + " edit=" + edit + " dt=" + dt + " at " + x + "," + y);
                        actual.recycle();
                        committed.recycle();
                    }
                }
            }
        }
    }

    private void compare(Bitmap expected, Bitmap actual, int l, int t, int r, int b, String message) {
        for (int y = t; y < b; y++) for (int x = l; x < r; x++)
            check(expected.getPixel(x, y) == actual.getPixel(x, y), message + " at " + x + "," + y);
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void append(TerminalEmulator emulator, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static final class Fixture implements AutoCloseable {
        final MagicDeskTerminalRenderer renderer;
        final TerminalScrollAnimation animation = new TerminalScrollAnimation();
        final TerminalEmulator emulator;
        final int cw, ch;
        long now;

        Fixture(Typeface font) {
            this(font, 8);
        }

        Fixture(Typeface font, int rows) {
            renderer = new MagicDeskTerminalRenderer(font, 18);
            cw = Math.round(renderer.cellWidth());
            ch = Math.round(renderer.cellHeight());
            emulator = new TerminalEmulator(new TerminalOutput() {
                @Override public void write(byte[] bytes, int offset, int length) { }
                @Override public void titleChanged(String oldTitle, String newTitle) { }
                @Override public void onCopyTextToClipboard(String text) { }
                @Override public void onPasteTextFromClipboard() { }
                @Override public void onBell() { }
                @Override public void onColorsChanged() { }
            }, 8, rows, cw, ch, 100, null);
            emulator.setImageFactory(AndroidTerminalImages.FACTORY);
            emulator.setScrollListener(new TerminalEmulator.ScrollListener() {
                @Override public void onScroll(int l, int t, int r, int b, int rows) {
                    animation.beforeScroll(emulator, renderer, l, t, r, b, rows, 32, now);
                }
                @Override public void onCellsChanged(int l, int t, int r, int b) {
                    animation.cellsChanged(emulator, l, t, r, b);
                }
                @Override public void onScrollComplete() { animation.scrollComplete(emulator); }
                @Override public void onScreenReset() { animation.reset(); }
            });
        }

        void fill() {
            for (int row = 0; row < 8; row++)
                append(emulator, "\033[" + (row + 1) + ";1H\033[48;5;" + (row + 1) + "mrow" + row + "    ");
            append(emulator, "\033[0m");
            // Both outgoing rows and the translated live region can contain images.
            append(emulator, "\033[1;2H\033_Ga=T,f=24,s=1,v=1,c=2,r=8,C=1;/wAA\033\\");
        }

        void edit(int sign, int top, int bottom, int count) {
            String sequence = "\033[" + (top + 1) + ";" + bottom + "r\033[" + (top + 1) + ";1H"
                    + "\033[" + count + (sign > 0 ? "L" : "M") + "\033[1;" + emulator.mRows + "r";
            // No callback or parser state depends on all bytes arriving together.
            for (int i = 0; i < sequence.length(); i++) append(emulator, sequence.substring(i, i + 1));
        }

        Bitmap committed() {
            Bitmap result = Bitmap.createBitmap(cw * 8, ch * emulator.mRows, Bitmap.Config.ARGB_8888);
            renderer.draw(new Canvas(result), TerminalFrame.capture(emulator, 0, emulator.mRows), 0, emulator.mRows, 0, Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0, false);
            return result;
        }

        Bitmap frame() {
            if (!animation.advance(now)) return committed();
            Bitmap result = Bitmap.createBitmap(cw * 8, ch * emulator.mRows, Bitmap.Config.ARGB_8888);
            animation.draw(new Canvas(result), emulator, renderer, false);
            return result;
        }

        @Override public void close() { emulator.setScrollListener(null); }
    }
}
