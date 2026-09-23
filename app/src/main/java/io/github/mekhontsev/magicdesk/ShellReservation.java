package io.github.mekhontsev.magicdesk;

import java.util.Objects;

/** An edge exclusion, not necessarily the surface's painted or interactive rectangle. */
record ShellReservation(Edge edge, Origin origin, int depth, int start, int end,
        boolean windows) {
    enum Edge { LEFT, TOP, RIGHT, BOTTOM }
    enum Origin { OUTPUT, PLACEMENT }

    ShellReservation {
        Objects.requireNonNull(edge);
        Objects.requireNonNull(origin);
        if (depth < 0 || (origin == Origin.OUTPUT && end < start)) {
            throw new IllegalArgumentException("Invalid shell reservation");
        }
    }

    // Absolute root-edge distances overlap by union, never by addition. Ranges
    // are half-open in scope coordinates; an X11 adapter converts inclusive ends.
    static ShellReservation absolute(final Edge edge, final int depth,
            final int start, final int end) {
        return new ShellReservation(edge, Origin.OUTPUT, depth, start, end, true);
    }

    // Placement-relative zones include the anchored margin. AVAILABLE placement
    // makes successive exclusive panels stack; CONTENT placement can overlap them.
    static ShellReservation exclusive(final Edge edge, final int depth,
            final boolean windows) {
        return new ShellReservation(edge, Origin.PLACEMENT, depth, 0, 0, windows);
    }
}
