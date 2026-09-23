package io.github.mekhontsev.magicdesk.wayland;

/** A rendered rectangle in surface-family coordinates, not a client size request. */
public record WaylandViewport(int x, int y, int width, int height) {
    public WaylandViewport {
        if (x < -16384 || y < -16384 || x > 16384 || y > 16384
                || width < 1 || height < 1 || width > 4096 || height > 4096)
            throw new IllegalArgumentException("Invalid Wayland output viewport");
    }
}
