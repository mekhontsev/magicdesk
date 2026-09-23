package io.github.mekhontsev.magicdesk;

import java.util.List;
import java.util.Objects;

/** Committed layout intent, independent of native surfaces, task areas and focus grants. */
record ShellSurface(String id, boolean mapped, Layer layer, Keyboard keyboard, Placement placement,
        Margins paintExtension, Input input, List<ShellReservation> reservations) {
    enum Layer { BACKGROUND, BOTTOM, TOP, OVERLAY }
    enum Keyboard { NONE, ON_DEMAND, EXCLUSIVE }
    enum Input { NONE, CONTENT, PAINT }
    enum Reference { OUTPUT, CONTENT, AVAILABLE, PANEL }

    static final int LEFT = 1;
    static final int TOP = 2;
    static final int RIGHT = 4;
    static final int BOTTOM = 8;

    record Margins(int left, int top, int right, int bottom) {
        static final Margins NONE = new Margins(0, 0, 0, 0);
    }

    record Placement(Reference reference, int anchors, int width, int height,
            Margins margins) {
        Placement {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(margins);
            if ((anchors & ~15) != 0 || width < 0 || height < 0
                    || (width == 0 && (anchors & (LEFT | RIGHT)) != (LEFT | RIGHT))
                    || (height == 0 && (anchors & (TOP | BOTTOM)) != (TOP | BOTTOM))) {
                throw new IllegalArgumentException("Invalid shell placement");
            }
        }
    }

    ShellSurface {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Surface identity is required");
        }
        Objects.requireNonNull(layer);
        Objects.requireNonNull(keyboard);
        Objects.requireNonNull(placement);
        Objects.requireNonNull(paintExtension);
        if (paintExtension.left() < 0 || paintExtension.top() < 0
                || paintExtension.right() < 0 || paintExtension.bottom() < 0) {
            throw new IllegalArgumentException("Paint extension must be nonnegative");
        }
        Objects.requireNonNull(input);
        reservations = List.copyOf(reservations);
        for (ShellReservation reservation : reservations) {
            if (reservation.origin() == ShellReservation.Origin.PLACEMENT) {
                final int anchor = switch (reservation.edge()) {
                    case LEFT -> LEFT;
                    case TOP -> TOP;
                    case RIGHT -> RIGHT;
                    case BOTTOM -> BOTTOM;
                };
                if ((placement.anchors() & anchor) == 0) {
                    throw new IllegalArgumentException("Exclusive edge must be anchored");
                }
            }
        }
    }

    ShellSurface withId(final String identity) {
        return new ShellSurface(identity, mapped, layer, keyboard, placement,
                paintExtension, input, reservations);
    }
}
