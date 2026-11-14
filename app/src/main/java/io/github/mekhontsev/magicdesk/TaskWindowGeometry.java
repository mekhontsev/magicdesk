package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Task-local content size and its display-space footprint share an unscaled origin. */
public final class TaskWindowGeometry {
    private TaskWindowGeometry() { }

    public static boolean validScale(float scale) {
        return Float.isFinite(scale) && scale > 0;
    }

    public static Rect displayBounds(Rect logical, float scale) {
        return scaledSize(logical, scale, false);
    }

    public static Rect logicalBounds(Rect display, float scale) {
        return scaledSize(display, 1.0 / scale, true);
    }

    private static Rect scaledSize(Rect bounds, double scale, boolean outward) {
        if (!Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("invalid window scale");
        }
        Rect result = new Rect();
        if (bounds == null) return result;
        result.left = bounds.left;
        result.top = bounds.top;
        result.right = edge(bounds.left, (long) bounds.right - bounds.left, scale, outward);
        result.bottom = edge(bounds.top, (long) bounds.bottom - bounds.top, scale, outward);
        return result;
    }

    private static int edge(int origin, long size, double scale, boolean outward) {
        double extent = size * scale;
        if (!Double.isFinite(extent) || extent > Integer.MAX_VALUE
                || extent < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("window extent overflow");
        }
        long value = origin + (outward ? (long) Math.ceil(extent) : Math.round(extent));
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("window coordinate overflow");
        }
        return (int) value;
    }
}
