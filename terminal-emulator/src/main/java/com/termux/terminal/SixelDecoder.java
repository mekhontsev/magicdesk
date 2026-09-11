package com.termux.terminal;

import java.util.Arrays;

/** Incremental DEC raster decoder. Storage, repeat counts and drawing work are independently bounded. */
final class SixelDecoder {
    private final int[] palette = new int[256];
    private final int background;
    private int[] pixels = new int[0];
    private int stride, capacityHeight, width, height, x, y, color = 1;
    private int command, parameter, parameterCount;
    private final int[] parameters = new int[5];
    private long work;
    private boolean failed;

    SixelDecoder(boolean transparent, int background) {
        this.background = transparent ? 0 : background;
        int[] defaults = {0xff000000, 0xff3333cc, 0xffcc2121, 0xff33cc33,
                0xffcc33cc, 0xff33cccc, 0xffcccc33, 0xff878787,
                0xff424242, 0xff545499, 0xff994242, 0xff549954,
                0xff995499, 0xff549999, 0xff999954, 0xffcccccc};
        Arrays.fill(palette, 0xff000000);
        System.arraycopy(defaults, 0, palette, 0, defaults.length);
    }

    void accept(int ch) {
        if (failed) return;
        try {
            if (command != 0) {
                if (ch >= '0' && ch <= '9') {
                    parameter = Math.addExact(Math.multiplyExact(parameter, 10), ch - '0');
                    return;
                }
                if (ch == ';') { addParameter(); return; }
                finishCommand(ch);
                if (command == '!') { command = 0; return; }
                command = 0;
            }
            if (ch == '!' || ch == '"' || ch == '#') {
                command = ch; parameter = parameterCount = 0;
                Arrays.fill(parameters, 0);
            } else if (ch == '$') x = 0;
            else if (ch == '-') { x = 0; y += 6; }
            else if (ch >= '?' && ch <= '~') draw(ch - '?', 1);
        } catch (IllegalArgumentException | ArithmeticException ex) {
            failed = true;
            pixels = new int[0];
        }
    }

    private void addParameter() {
        if (parameterCount == parameters.length) throw new IllegalArgumentException("sixel parameters");
        parameters[parameterCount++] = parameter;
        parameter = 0;
    }

    private void finishCommand(int next) {
        addParameter();
        if (command == '!') {
            if (parameterCount != 1 || next < '?' || next > '~') throw new IllegalArgumentException("sixel repeat");
            draw(next - '?', Math.max(1, parameters[0]));
        } else if (command == '"') {
            if (parameterCount >= 4 && parameters[2] != 0 && parameters[3] != 0)
                grow(parameters[2], parameters[3]);
        } else if (command == '#') {
            color = parameters[0];
            if (color >= palette.length) throw new IllegalArgumentException("sixel palette");
            if (parameterCount == 5) {
                if (parameters[1] == 2) {
                    int r = percent(parameters[2]), g = percent(parameters[3]), b = percent(parameters[4]);
                    palette[color] = 0xff000000 | r << 16 | g << 8 | b;
                } else if (parameters[1] == 1) {
                    // DEC hue zero is blue, not red. HLS channels use percentages.
                    double hue = ((parameters[2] + 240) % 360) / 60.0;
                    double l = percent(parameters[3]) / 255.0, s = percent(parameters[4]) / 255.0;
                    double c = (1 - Math.abs(2 * l - 1)) * s, a = c * (1 - Math.abs(hue % 2 - 1));
                    double r = hue < 1 || hue >= 5 ? c : hue < 2 || hue >= 4 ? a : 0;
                    double g = hue >= 1 && hue < 3 ? c : hue < 1 || hue < 4 ? a : 0;
                    double b = hue >= 3 && hue < 5 ? c : hue >= 2 ? a : 0;
                    double m = l - c / 2;
                    palette[color] = 0xff000000 | (int) Math.round((r + m) * 255) << 16
                            | (int) Math.round((g + m) * 255) << 8 | (int) Math.round((b + m) * 255);
                }
            }
        }
    }

    private static int percent(int value) {
        if (value > 100) throw new IllegalArgumentException("sixel color");
        return (value * 255 + 50) / 100;
    }

    private void draw(int bits, int count) {
        if (count > TerminalImage.MAX_DIMENSION || (work += (long) count * 6) > 32L * 1024 * 1024)
            throw new IllegalArgumentException("sixel work limit");
        grow(x + count, y + 6);
        for (int bit = 0; bit < 6; bit++) if ((bits & 1 << bit) != 0)
            Arrays.fill(pixels, (y + bit) * stride + x, (y + bit) * stride + x + count, palette[color]);
        x += count;
    }

    private void grow(int w, int h) {
        TerminalImage.checkSize(w, h);
        width = Math.max(width, w); height = Math.max(height, h);
        if (width <= stride && height <= capacityHeight) return;
        int newStride = Math.max(width, Math.min(TerminalImage.MAX_DIMENSION, Math.max(64, stride * 2)));
        int newHeight = Math.max(height, Math.min(TerminalImage.MAX_DIMENSION, Math.max(64, capacityHeight * 2)));
        if ((long) newStride * newHeight > TerminalImage.MAX_PIXELS) { newStride = width; newHeight = height; }
        TerminalImage.checkSize(newStride, newHeight);
        int[] next = new int[newStride * newHeight];
        if (background != 0) Arrays.fill(next, background);
        for (int row = 0; row < capacityHeight; row++)
            System.arraycopy(pixels, row * stride, next, row * newStride, Math.min(stride, newStride));
        pixels = next; stride = newStride; capacityHeight = newHeight;
    }

    TerminalImage finish(TerminalImage.Factory factory) {
        if (failed || width == 0 || height == 0) return null;
        int[] result = new int[width * height];
        for (int row = 0; row < height; row++) System.arraycopy(pixels, row * stride, result, row * width, width);
        pixels = new int[0];
        return factory.fromArgb(width, height, result);
    }
}
