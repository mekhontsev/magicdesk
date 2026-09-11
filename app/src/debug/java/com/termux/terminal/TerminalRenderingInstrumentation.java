package com.termux.terminal;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;

import io.github.mekhontsev.magicdesk.R;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Actual Android font/Canvas checks; no Desktop, shell service or visible Activity is needed. */
public final class TerminalRenderingInstrumentation extends Instrumentation {
    private int checks;

    @Override public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        final Bundle result = new Bundle();
        try {
            final Typeface family = getTargetContext().getResources().getFont(R.font.console_mono);
            checkFonts(family);
            for (final int width : new int[] {6, 9, 16, 23, 40}) {
                checkGeometry(width, width * 2 + 1);
            }
            checkPresentation(family);
            final File atlas = new File(getTargetContext().getCacheDir(), "terminal-rendering.png");
            try (FileOutputStream output = new FileOutputStream(atlas)) {
                final Bitmap bitmap = atlas(family);
                try {
                    require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "atlas encoding");
                } finally {
                    bitmap.recycle();
                }
            }
            result.putString("terminal_rendering", "PASS checks=" + checks + " atlas=" + atlas);
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError error) {
            result.putString("terminal_rendering", "FAIL checks=" + checks + " " + error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkFonts(final Typeface family) {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (final float size : new float[] {8, 14, 25.5f, 45.5f, 130}) {
            final MagicDeskTerminalRenderer renderer = new MagicDeskTerminalRenderer(family, size);
            require(renderer.cellWidth() == Math.round(renderer.cellWidth()), "fractional cell width");
            require(renderer.cellHeight() == Math.round(renderer.cellHeight()), "fractional cell height");
            paint.setTextSize(size);
            float regularWidth = 0;
            for (int style = 0; style < 4; style++) {
                final Typeface face = Typeface.create(family, style);
                paint.setTypeface(face);
                require(face.getStyle() == style, "missing face " + style);
                if (style == 0) regularWidth = paint.measureText("M");
                require(Math.abs(paint.measureText("M") - regularWidth) < 0.05f, "style changed grid width");
                require(paint.measureText("M") <= renderer.cellWidth(), "text exceeds grid");
                for (final String glyph : new String[] {"A", "\u0416", "\ue0a0", "\uf013", "\uf07b", "\uf120", "\udb80\udc01"}) {
                    require(paint.hasGlyph(glyph), "missing glyph " + glyph + " style=" + style);
                }
            }
        }
    }

    private void checkGeometry(final int width, final int height) {
        final TerminalCellGeometry geometry = new TerminalCellGeometry();
        final Bitmap bitmap = Bitmap.createBitmap(width * 3, height * 3, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        try {
            for (int cp = 0x2500; cp <= 0x28ff; cp++) {
                if (!TerminalCellGeometry.supports(cp)) continue;
                bitmap.eraseColor(Color.BLACK);
                require(geometry.draw(canvas, cp, width, height, width, height, Color.WHITE), "glyph declined");
                final int ink = ink(bitmap, width, height, width * 2, height * 2);
                require(cp == 0x2800 ? ink == 0 : ink > 0, "blank glyph " + Integer.toHexString(cp));
                require(ink(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight()) == ink, "glyph escaped cell");
            }
            for (final int cp : new int[] {0x2500, 0x2501, 0x2550}) {
                bitmap.eraseColor(Color.BLACK);
                for (int col = 0; col < 3; col++) geometry.draw(canvas, cp, col * width, height, width, height, Color.WHITE);
                for (int x = 1; x < width * 3; x++) {
                    for (int y = height; y < height * 2; y++) {
                        require(bitmap.getPixel(x, y) == bitmap.getPixel(0, y), "horizontal seam");
                    }
                }
            }
            for (final int cp : new int[] {0x2502, 0x2503, 0x2551}) {
                bitmap.eraseColor(Color.BLACK);
                for (int row = 0; row < 3; row++) geometry.draw(canvas, cp, width, row * height, width, height, Color.WHITE);
                for (int y = 1; y < height * 3; y++) {
                    for (int x = width; x < width * 2; x++) {
                        require(bitmap.getPixel(x, y) == bitmap.getPixel(x, 0), "vertical seam");
                    }
                }
            }
            bitmap.eraseColor(Color.BLACK);
            geometry.draw(canvas, 0x2588, width, height, width, height, Color.WHITE);
            require(ink(bitmap, width, height, width * 2, height * 2) == width * height, "full block margins");
            int previousShade = 0;
            for (int cp = 0x2591; cp <= 0x2593; cp++) {
                bitmap.eraseColor(Color.BLACK);
                geometry.draw(canvas, cp, width, height, width, height, Color.WHITE);
                final int shade = ink(bitmap, width, height, width * 2, height * 2);
                require(shade > previousShade && shade < width * height, "shade density");
                previousShade = shade;
            }
            bitmap.eraseColor(Color.BLACK);
            geometry.draw(canvas, 0x2592, width, height, width, height, Color.WHITE);
            require(bitmap.getPixel(width, height) != bitmap.getPixel(width + 1, height), "shade horizontal pattern");
            require(bitmap.getPixel(width, height) != bitmap.getPixel(width, height + 1), "shade vertical pattern");
            require(bitmap.getPixel(width, height) == bitmap.getPixel(width + 1, height + 1), "shade checkerboard");
            final int thin = Math.max(1, Math.round(width / 10.0f));
            final int cx = width + Math.round(width / 2.0f), cy = height + Math.round(height / 2.0f);
            final int railX = Math.round(cx - thin * 2 - thin / 2.0f);
            final int railY = Math.round(cy - thin * 2 - thin / 2.0f);
            bitmap.eraseColor(Color.BLACK);
            geometry.draw(canvas, 0x2554, width, height, width, height, Color.WHITE);
            require(bitmap.getPixel(railX, railY) == Color.WHITE, "double corner disconnected");
            require(bitmap.getPixel(cx, cy) == Color.BLACK, "double corner filled its gap");
            bitmap.eraseColor(Color.BLACK);
            geometry.draw(canvas, 0x256c, width, height, width, height, Color.WHITE);
            require(bitmap.getPixel(cx, cy) == Color.BLACK, "double cross filled its gap");
            for (int dot = 0; dot < 8; dot++) {
                bitmap.eraseColor(Color.BLACK);
                geometry.draw(canvas, 0x2800 | (1 << dot), width, height, width, height, Color.WHITE);
                final int col = dot == 3 || dot == 4 || dot == 5 || dot == 7 ? 1 : 0;
                final int row = dot == 6 || dot == 7 ? 3 : dot >= 3 ? dot - 3 : dot;
                final int x = width + (int) (width * (col == 0 ? 0.25f : 0.75f));
                final int y = height + (int) (height * (row + 0.5f) / 4);
                require(bitmap.getPixel(x, y) != Color.BLACK, "Braille dot position " + dot);
            }
            for (int cp = 0xe0b0; cp <= 0xe0b7; cp++) {
                bitmap.eraseColor(Color.BLACK);
                geometry.draw(canvas, cp, width, height, width, height, Color.WHITE);
                final int count = ink(bitmap, width, height, width * 2, height * 2);
                require(count > 0, "Powerline blank");
                require(count == ink(bitmap, 0, 0, width * 3, height * 3), "Powerline escaped cell");
            }
            require(!geometry.draw(canvas, 'A', 0, 0, width, height, Color.WHITE), "ordinary glyph intercepted");
        } finally {
            bitmap.recycle();
        }
    }

    private void checkPresentation(final Typeface family) {
        final MagicDeskTerminalRenderer renderer = new MagicDeskTerminalRenderer(family, 25.5f);
        final int width = (int) renderer.cellWidth(), height = (int) renderer.cellHeight();
        final TerminalEmulator emulator = emulator(8, 2, width, height);
        final Bitmap bitmap = Bitmap.createBitmap(width * 8, height * 2, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        try {
            append(emulator, "\033[2J\033[H\033[31m\u2588\033[7m\u2588\033[0;8m\u2588\033[0m\ue0a0e\u0301\u754c");
            final int cursor = emulator.getCursorCol();
            renderer.draw(canvas, emulator, 0, 2, Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0, false);
            require(bitmap.getPixel(0, 0) == emulator.mColors.mCurrentColors[1], "block foreground");
            require(bitmap.getPixel(width, 0) == Color.BLACK, "inverse block");
            require(bitmap.getPixel(width * 2, 0) == Color.BLACK, "invisible block");
            require(ink(bitmap, width * 3, 0, width * 4, height) > 0, "Nerd icon missing");
            require(ink(bitmap, width * 4, 0, width * 5, height) > 0, "combining cluster missing");
            require(ink(bitmap, width * 5, 0, width * 7, height) > 0, "wide glyph missing");
            require(cursor == 7 && emulator.getCursorCol() == cursor, "render changed logical columns");
            append(emulator, "\033[2J\033[H\033]8;;https://example.com\007\u2800\033]8;;\007");
            renderer.draw(canvas, emulator, 0, 2, Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0, false);
            require(ink(bitmap, 0, 0, width, height) > 0, "geometric hyperlink underline missing");
            renderer.draw(canvas, emulator, 0, 2, 0, 0, 0, 0, false);
            require(bitmap.getPixel(0, 0) != Color.BLACK, "selection missing");
        } finally {
            bitmap.recycle();
        }
    }

    private Bitmap atlas(final Typeface family) {
        final MagicDeskTerminalRenderer renderer = new MagicDeskTerminalRenderer(family, 25.5f);
        final int width = (int) renderer.cellWidth(), height = (int) renderer.cellHeight();
        final TerminalEmulator emulator = emulator(54, 32, width, height);
        final StringBuilder text = new StringBuilder("JetBrains Mono Nerd Font Mono\r\n");
        for (final String style : new String[] {"0", "1", "3", "1;3"}) {
            text.append("\033[").append(style).append("mAaZz 0123 \u041f\u0440\u0438\u0432\u0435\u0442 e\u0301 \u754c \uf013 \uf07b \uf120 \ue0a0\033[0m\r\n");
        }
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 16; col++) text.appendCodePoint(0x2500 + row * 16 + col);
            text.append("\r\n");
        }
        text.append("\u250c\u2500\u2500\u2500\u252c\u2500\u2500\u2500\u2510  \u2554\u2550\u2550\u2550\u2566\u2550\u2550\u2550\u2557\r\n")
                .append("\u2502   \u2502   \u2502  \u2551   \u2551   \u2551\r\n")
                .append("\u2514\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2518  \u255a\u2550\u2550\u2550\u2569\u2550\u2550\u2550\u255d\r\n");
        for (int cp = 0x2580; cp <= 0x259f; cp++) text.appendCodePoint(cp);
        text.append("\r\n");
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 32; col++) text.appendCodePoint(0x2800 + row * 32 + col);
            text.append("\r\n");
        }
        text.append("\033[44;37m shell \033[34;42m\ue0b0\033[30m termux \033[32;40m\ue0b0\033[0m\r\n");
        for (int cp = 0xe0b0; cp <= 0xe0b7; cp++) text.appendCodePoint(cp);
        append(emulator, text.toString());
        final Bitmap bitmap = Bitmap.createBitmap(width * 54, height * 32, Bitmap.Config.ARGB_8888);
        renderer.draw(new Canvas(bitmap), emulator, 0, 32, Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0, false);
        return bitmap;
    }

    private static TerminalEmulator emulator(final int columns, final int rows, final int width, final int height) {
        final TerminalOutput output = new TerminalOutput() {
            @Override public void write(final byte[] data, final int offset, final int count) { }
            @Override public void titleChanged(final String oldTitle, final String newTitle) { }
            @Override public void onCopyTextToClipboard(final String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        return new TerminalEmulator(output, columns, rows, width, height, 100, null);
    }

    private static void append(final TerminalEmulator emulator, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static int ink(final Bitmap bitmap, final int left, final int top, final int right, final int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (bitmap.getPixel(x, y) != Color.BLACK) count++;
            }
        }
        return count;
    }

    private void require(final boolean condition, final String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
