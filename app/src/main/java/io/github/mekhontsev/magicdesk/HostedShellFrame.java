package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A rendered family viewport and exact input in the same protocol-local coordinates. */
record HostedShellFrame(ShellBounds viewport, boolean inputComplete, List<ShellBounds> input) {
    HostedShellFrame {
        Objects.requireNonNull(viewport);
        if (viewport.isEmpty()) throw new IllegalArgumentException("Empty shell viewport");
        input = List.copyOf(input);
        if (!inputComplete && !input.isEmpty())
            throw new IllegalArgumentException("Incomplete input must not capture a bounding box");
    }

    /** Match the buffer's stretch to the Android window; round input inward, never over a hole. */
    List<ShellBounds> inputPixels(int width, int height) {
        if (!inputComplete || width < 1 || height < 1) return List.of();
        var result = new ArrayList<ShellBounds>();
        for (var rect : input) {
            var clipped = rect.intersect(viewport);
            if (clipped.isEmpty()) continue;
            int left = ceil((long) (clipped.left() - viewport.left()) * width, viewport.width());
            int top = ceil((long) (clipped.top() - viewport.top()) * height, viewport.height());
            int right = (int) ((long) (clipped.right() - viewport.left()) * width / viewport.width());
            int bottom = (int) ((long) (clipped.bottom() - viewport.top()) * height / viewport.height());
            if (left < right && top < bottom) result.add(new ShellBounds(left, top, right, bottom));
        }
        return List.copyOf(result);
    }

    private static int ceil(long value, int divisor) { return (int) ((value + divisor - 1) / divisor); }
}
