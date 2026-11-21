package com.termux.terminal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Cell-filling terminal glyphs. Coordinates come only from the renderer's logical grid. */
final class TerminalCellGeometry {
    // U+2500..257F: two bits per arm (left, up, right, down), 1=light, 2=heavy, 3=double.
    // Unicode Box Drawing character names define the arms; curves/dashes use the same topology.
    private static final int[] BOX_ARMS = {
        0x11, 0x22, 0x44, 0x88, 0x11, 0x22, 0x44, 0x88, // 2500
        0x11, 0x22, 0x44, 0x88, 0x50, 0x60, 0x90, 0xa0, // 2508
        0x41, 0x42, 0x81, 0x82, 0x14, 0x24, 0x18, 0x28, // 2510
        0x05, 0x06, 0x09, 0x0a, 0x54, 0x64, 0x58, 0x94, // 2518
        0x98, 0x68, 0xa4, 0xa8, 0x45, 0x46, 0x49, 0x85, // 2520
        0x89, 0x4a, 0x86, 0x8a, 0x51, 0x52, 0x61, 0x62, // 2528
        0x91, 0x92, 0xa1, 0xa2, 0x15, 0x16, 0x25, 0x26, // 2530
        0x19, 0x1a, 0x29, 0x2a, 0x55, 0x56, 0x65, 0x66, // 2538
        0x59, 0x95, 0x99, 0x5a, 0x69, 0x96, 0xa5, 0x6a, // 2540
        0xa6, 0x9a, 0xa9, 0xaa, 0x11, 0x22, 0x44, 0x88, // 2548
        0x33, 0xcc, 0x70, 0xd0, 0xf0, 0x43, 0xc1, 0xc3, // 2550
        0x34, 0x1c, 0x3c, 0x07, 0x0d, 0x0f, 0x74, 0xdc, // 2558
        0xfc, 0x47, 0xcd, 0xcf, 0x73, 0xd1, 0xf3, 0x37, // 2560
        0x1d, 0x3f, 0x77, 0xdd, 0xff, 0x50, 0x41, 0x05, // 2568
        0x14, 0x00, 0x00, 0x00, 0x01, 0x04, 0x10, 0x40, // 2570
        0x02, 0x08, 0x20, 0x80, 0x21, 0x84, 0x12, 0x48, // 2578
    };
    private static final int[] QUADRANTS = {4, 8, 1, 13, 9, 7, 11, 2, 6, 14};
    private static final int[] BRAILLE_BITS = {0, 1, 2, 6, 3, 4, 5, 7};

    private final Paint paint = new Paint();
    private final Path path = new Path();
    private final Path[] shades = {new Path(), new Path(), new Path()};
    private float shadeWidth, shadeHeight;

    boolean draw(final Canvas canvas, final int codePoint, final float left, final float top,
            final float width, final float height, final int color) {
        if (!supports(codePoint)) {
            return false;
        }
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(false);
        final int saved = canvas.save();
        canvas.translate(left, top);
        canvas.clipRect(0, 0, width, height);
        final float thin = Math.max(1, Math.round(Math.min(width, height) / 10));
        if (codePoint >= 0x2800 && codePoint <= 0x28ff) {
            braille(canvas, codePoint - 0x2800, width, height);
        } else if (codePoint >= 0x2580 && codePoint <= 0x259f) {
            block(canvas, codePoint, width, height);
        } else if (codePoint >= 0xe0b0) {
            powerline(canvas, codePoint, width, height, thin);
        } else if (codePoint >= 0x256d && codePoint <= 0x2573) {
            curve(canvas, codePoint, width, height, thin);
        } else {
            final int dashCount = codePoint >= 0x2504 && codePoint <= 0x2507 ? 3
                    : codePoint >= 0x2508 && codePoint <= 0x250b ? 4
                    : codePoint >= 0x254c && codePoint <= 0x254f ? 2 : 0;
            final int arms = BOX_ARMS[codePoint - 0x2500];
            if (dashCount != 0) {
                dashed(canvas, arms, dashCount, width, height, thin);
            } else {
                for (int direction = 0; direction < 4; direction++) {
                    arm(canvas, arms, direction, width, height, thin);
                }
            }
        }
        canvas.restoreToCount(saved);
        return true;
    }

    static boolean supports(final int codePoint) {
        return codePoint >= 0x2500 && codePoint <= 0x259f
                || codePoint >= 0x2800 && codePoint <= 0x28ff
                || codePoint >= 0xe0b0 && codePoint <= 0xe0b7;
    }

    private static int weight(final int arms, final int direction) {
        return (arms >> (direction * 2)) & 3;
    }

    private void arm(final Canvas canvas, final int arms, final int direction,
            final float width, final float height, final float thin) {
        final int style = weight(arms, direction);
        if (style == 0) {
            return;
        }
        final boolean horizontal = (direction & 1) == 0;
        final int negative = horizontal ? 1 : 0;
        final int positive = horizontal ? 3 : 2;
        final int before = weight(arms, negative), after = weight(arms, positive);
        final boolean opposite = weight(arms, (direction + 2) % 4) != 0;
        final float sign = direction < 2 ? -1 : 1;
        final float center = Math.round((horizontal ? width : height) / 2);
        final float crossCenter = Math.round((horizontal ? height : width) / 2);
        final float length = horizontal ? width : height;
        final float gap = thin * 2;
        final float thickness = style == 2 ? thin * 2 : thin;
        for (int lane = style == 3 ? -1 : 0; lane <= (style == 3 ? 1 : 0); lane += 2) {
            float end = center;
            if (style == 3) {
                final int facing = lane < 0 ? before : after;
                final int other = lane < 0 ? after : before;
                if (facing == 3) {
                    end += sign * gap;
                } else if (other == 3 && !opposite) {
                    end -= sign * gap;
                }
            } else if ((before == 3 || after == 3) && !opposite) {
                end += sign * gap * (before == 3 && after == 3 ? 1 : -1);
            }
            // Extend to the crossing rail, including mixed heavy/light corners.
            final float overlap = Math.max(thin,
                    Math.max(before == 2 ? thin * 2 : thin, after == 2 ? thin * 2 : thin)) / 2;
            final float from = sign < 0 ? 0 : Math.round(end - overlap);
            final float to = sign < 0 ? Math.round(end + overlap) : length;
            final float cross = Math.round(crossCenter + lane * gap - thickness / 2);
            if (horizontal) {
                canvas.drawRect(from, cross, to, cross + thickness, paint);
            } else {
                canvas.drawRect(cross, from, cross + thickness, to, paint);
            }
        }
    }

    private void dashed(final Canvas canvas, final int arms, final int count,
            final float width, final float height, final float thin) {
        final boolean horizontal = weight(arms, 0) != 0;
        final float length = horizontal ? width : height;
        final float thickness = weight(arms, horizontal ? 0 : 1) == 2 ? thin * 2 : thin;
        final float cross = Math.round(Math.round((horizontal ? height : width) / 2) - thickness / 2);
        final float unit = length / (count * 3 - 1);
        for (int i = 0; i < count; i++) {
            final float from = Math.round(i * 3 * unit);
            final float to = Math.round((i * 3 + 2) * unit);
            if (horizontal) {
                canvas.drawRect(from, cross, to, cross + thickness, paint);
            } else {
                canvas.drawRect(cross, from, cross + thickness, to, paint);
            }
        }
    }

    private void curve(final Canvas canvas, final int codePoint, final float width,
            final float height, final float thin) {
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(thin);
        paint.setStrokeCap(Paint.Cap.BUTT);
        path.rewind();
        if (codePoint >= 0x2571) {
            if (codePoint != 0x2572) {
                path.moveTo(width, 0);
                path.lineTo(0, height);
            }
            if (codePoint != 0x2571) {
                path.moveTo(0, 0);
                path.lineTo(width, height);
            }
        } else {
            final float cx = Math.round(width / 2) + (thin % 2) / 2;
            final float cy = Math.round(height / 2) + (thin % 2) / 2;
            final int arms = BOX_ARMS[codePoint - 0x2500];
            final float dx = weight(arms, 0) != 0 ? -1 : 1;
            final float dy = weight(arms, 1) != 0 ? -1 : 1;
            final float radius = Math.min(width, height) / 4;
            path.moveTo(dx < 0 ? 0 : width, cy);
            path.lineTo(cx + dx * radius, cy);
            path.quadTo(cx, cy, cx, cy + dy * radius);
            path.lineTo(cx, dy < 0 ? 0 : height);
        }
        canvas.drawPath(path, paint);
    }

    private void block(final Canvas canvas, final int codePoint, final float width, final float height) {
        if (codePoint == 0x2580 || codePoint == 0x2594) {
            canvas.drawRect(0, 0, width, Math.round(height * (codePoint == 0x2580 ? 0.5f : 0.125f)), paint);
        } else if (codePoint <= 0x2588) {
            canvas.drawRect(0, Math.round(height * (0x2588 - codePoint) / 8), width, height, paint);
        } else if (codePoint <= 0x258f) {
            canvas.drawRect(0, 0, Math.round(width * (0x2590 - codePoint) / 8), height, paint);
        } else if (codePoint == 0x2590 || codePoint == 0x2595) {
            canvas.drawRect(Math.round(width * (codePoint == 0x2590 ? 0.5f : 0.875f)), 0, width, height, paint);
        } else if (codePoint <= 0x2593) {
            prepareShades(width, height);
            canvas.drawPath(shades[codePoint - 0x2591], paint);
        } else {
            final int mask = QUADRANTS[codePoint - 0x2596];
            final float middleX = Math.round(width / 2), middleY = Math.round(height / 2);
            for (int i = 0; i < 4; i++) {
                if ((mask & (1 << i)) != 0) {
                    canvas.drawRect((i & 1) == 0 ? 0 : middleX, i < 2 ? 0 : middleY,
                            (i & 1) == 0 ? middleX : width, i < 2 ? middleY : height, paint);
                }
            }
        }
    }

    private void prepareShades(final float width, final float height) {
        if (shadeWidth == width && shadeHeight == height) {
            return;
        }
        shadeWidth = width;
        shadeHeight = height;
        for (final Path shade : shades) {
            shade.rewind();
        }
        // Cache an ordered 2x2 pattern at this cell size, rather than submitting each dot per draw.
        final int xSteps = Math.max(2, Math.round(width / 2) * 2);
        final int ySteps = Math.max(2, Math.round(height / 2) * 2);
        for (int y = 0; y < ySteps; y++) {
            for (int x = 0; x < xSteps; x++) {
                final int level = (((x ^ y) & 1) << 1) | (y & 1);
                for (int shade = level; shade < shades.length; shade++) {
                    shades[shade].addRect(Math.round(x * width / xSteps), Math.round(y * height / ySteps),
                            Math.round((x + 1) * width / xSteps), Math.round((y + 1) * height / ySteps),
                            Path.Direction.CW);
                }
            }
        }
    }

    private void braille(final Canvas canvas, final int dots, final float width, final float height) {
        paint.setAntiAlias(true);
        final float radius = Math.min(width / 6, height / 12);
        for (int i = 0; i < BRAILLE_BITS.length; i++) {
            if ((dots & (1 << BRAILLE_BITS[i])) != 0) {
                canvas.drawCircle(width * (i < 4 ? 0.25f : 0.75f),
                        height * ((i % 4) + 0.5f) / 4, radius, paint);
            }
        }
    }

    private void powerline(final Canvas canvas, final int codePoint, final float width,
            final float height, final float thin) {
        paint.setAntiAlias(true);
        final boolean outline = (codePoint & 1) != 0;
        final boolean left = (codePoint & 2) != 0;
        if (left) {
            canvas.translate(width, 0);
            canvas.scale(-1, 1);
        }
        path.rewind();
        path.moveTo(0, 0);
        if (codePoint < 0xe0b4) {
            path.lineTo(width, height / 2);
            path.lineTo(0, height);
        } else {
            path.cubicTo(width * 1.333333f, 0, width * 1.333333f, height, 0, height);
        }
        if (outline) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(thin);
        } else {
            path.close();
        }
        canvas.drawPath(path, paint);
    }
}
