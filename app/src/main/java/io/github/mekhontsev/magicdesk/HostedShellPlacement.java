package io.github.mekhontsev.magicdesk;

/** Converts a family-local painted viewport to workspace pixels without enlarging its reservation. */
final class HostedShellPlacement {
    static HostedShellFrame clip(ShellBounds content, HostedShellFrame frame, ShellBounds output, int density) {
        if (density <= 0) throw new IllegalArgumentException("Shell density must be positive");
        var available = new ShellBounds(
                unit(output.left() - content.left(), density, true),
                unit(output.top() - content.top(), density, true),
                unit(output.right() - content.left(), density, false),
                unit(output.bottom() - content.top(), density, false));
        var viewport = frame.viewport().intersect(available);
        if (viewport.isEmpty()) return null;
        return viewport.equals(frame.viewport()) ? frame : new HostedShellFrame(viewport, frame.inputComplete(), frame.input());
    }

    private static int unit(int pixels, int density, boolean start) {
        long value = (long) pixels * 160;
        return Math.toIntExact(start ? -Math.floorDiv(-value, density) : Math.floorDiv(value, density));
    }

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
