package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.util.Objects;

/** Display-independent window geometry relative to the desktop work area. */
final class RelativeWindowBounds {
    static final int SCALE = 10_000;

    final int x;
    final int y;
    final int width;
    final int height;

    RelativeWindowBounds(
            final int x,
            final int y,
            final int width,
            final int height) {
        this.x = clamp(x, 0, SCALE);
        this.y = clamp(y, 0, SCALE);
        this.width = clamp(width, 1, SCALE);
        this.height = clamp(height, 1, SCALE);
    }

    static RelativeWindowBounds from(
            final Rect bounds,
            final Rect workArea) {
        if (bounds == null || workArea == null) {
            return null;
        }
        return from(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                workArea.left,
                workArea.top,
                workArea.right,
                workArea.bottom);
    }

    static RelativeWindowBounds from(
            final int boundsLeft,
            final int boundsTop,
            final int boundsRight,
            final int boundsBottom,
            final int areaLeft,
            final int areaTop,
            final int areaRight,
            final int areaBottom) {
        final int areaWidth = areaRight - areaLeft;
        final int areaHeight = areaBottom - areaTop;
        final int boundsWidth = boundsRight - boundsLeft;
        final int boundsHeight = boundsBottom - boundsTop;
        if (areaWidth <= 0 || areaHeight <= 0
                || boundsWidth <= 0 || boundsHeight <= 0) {
            return null;
        }
        final int width = Math.min(boundsWidth, areaWidth);
        final int height = Math.min(boundsHeight, areaHeight);
        final int horizontalTravel = Math.max(0, areaWidth - width);
        final int verticalTravel = Math.max(0, areaHeight - height);
        return new RelativeWindowBounds(
                ratio(boundsLeft - areaLeft, horizontalTravel),
                ratio(boundsTop - areaTop, verticalTravel),
                ratio(width, areaWidth),
                ratio(height, areaHeight));
    }

    Rect resolve(final Rect workArea) {
        if (workArea == null) {
            return new Rect();
        }
        final int[] resolved = resolve(
                workArea.left,
                workArea.top,
                workArea.right,
                workArea.bottom);
        return new Rect(
                resolved[0], resolved[1], resolved[2], resolved[3]);
    }

    int[] resolve(
            final int areaLeft,
            final int areaTop,
            final int areaRight,
            final int areaBottom) {
        final int areaWidth = areaRight - areaLeft;
        final int areaHeight = areaBottom - areaTop;
        if (areaWidth <= 0 || areaHeight <= 0) {
            return new int[] {0, 0, 0, 0};
        }
        final int resolvedWidth = clamp(
                scaled(width, areaWidth), 1, areaWidth);
        final int resolvedHeight = clamp(
                scaled(height, areaHeight), 1, areaHeight);
        final int horizontalTravel = areaWidth - resolvedWidth;
        final int verticalTravel = areaHeight - resolvedHeight;
        final int left = areaLeft + scaled(x, horizontalTravel);
        final int top = areaTop + scaled(y, verticalTravel);
        return new int[] {
                left,
                top,
                left + resolvedWidth,
                top + resolvedHeight};
    }

    private static int ratio(final int value, final int range) {
        if (range <= 0) {
            return 0;
        }
        return clamp((int) Math.round(
                (double) value * SCALE / range), 0, SCALE);
    }

    private static int scaled(final int value, final int range) {
        return (int) Math.round((double) value * range / SCALE);
    }

    private static int clamp(
            final int value,
            final int minimum,
            final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RelativeWindowBounds)) {
            return false;
        }
        final RelativeWindowBounds bounds = (RelativeWindowBounds) other;
        return x == bounds.x
                && y == bounds.y
                && width == bounds.width
                && height == bounds.height;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, width, height);
    }
}
