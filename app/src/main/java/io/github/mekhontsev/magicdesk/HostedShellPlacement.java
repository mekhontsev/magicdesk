package io.github.mekhontsev.magicdesk;

/** Converts a family-local painted viewport to workspace pixels without enlarging its reservation. */
final class HostedShellPlacement {
    static ShellBounds bounds(ShellBounds content, HostedShellFrame frame, int density) {
        if (density <= 0) throw new IllegalArgumentException("Shell density must be positive");
        var paint = frame.viewport();
        int left = coordinate(content.left(), paint.left(), density, false);
        int top = coordinate(content.top(), paint.top(), density, false);
        int right = coordinate(content.left(), paint.right(), density, true);
        int bottom = coordinate(content.top(), paint.bottom(), density, true);
        return new ShellBounds(left, top, right, bottom);
    }

    private static int coordinate(int origin, int units, int density, boolean end) {
        long value = (long) units * density;
        long pixels = end ? -Math.floorDiv(-value, 160) : Math.floorDiv(value, 160);
        return Math.toIntExact(origin + pixels);
    }

    private HostedShellPlacement() { }
}
