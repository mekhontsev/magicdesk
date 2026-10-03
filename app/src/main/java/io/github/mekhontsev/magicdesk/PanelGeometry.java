package io.github.mekhontsev.magicdesk;

import java.util.Objects;

/** Resolves a native panel's long and cross axes before the shared placement pass. */
record PanelGeometry(ShellPanel panel, int width, int height, int anchors,
        ShellSurface.Margins margins, boolean extendInset) {
    static PanelGeometry resolve(ShellPanel panel, float density, int availableWidth,
            int availableHeight, int automaticThickness, int preferredLength, int minimumLength) {
        Objects.requireNonNull(panel);
        if (!Float.isFinite(density) || density <= 0) throw new IllegalArgumentException("Invalid panel density");
        final var style = panel.style();
        final boolean vertical = panel.edge().vertical();
        final int availableLength = Math.max(1, vertical ? availableHeight : availableWidth);
        final int availableThickness = Math.max(1, vertical ? availableWidth : availableHeight);
        final int thickness = Math.max(1, Math.min(availableThickness, style.thicknessDp() == 0
                ? automaticThickness : Math.round(style.thicknessDp() * density)));
        final int minimum = Math.max(1, Math.min(minimumLength, availableLength));
        final int side = Math.min(Math.round(style.sideGapDp() * density), (availableLength - minimum) / 2);
        final int edge = Math.min(Math.round(style.edgeGapDp() * density), availableThickness - thickness);
        final int limit = availableLength - 2 * side;
        final int length = style.length() == ShellAppearance.Width.FILL ? limit
                : Math.min(limit, Math.max(minimum, Math.min(preferredLength, Math.round(style.maxLengthDp() * density))));
        final int start = vertical ? ShellSurface.TOP : ShellSurface.LEFT;
        final int end = vertical ? ShellSurface.BOTTOM : ShellSurface.RIGHT;
        final int alignment = style.length() == ShellAppearance.Width.FILL ? start | end
                : switch (style.alignment()) {
                    case START -> start;
                    case CENTER -> 0;
                    case END -> end;
                };
        final int anchor = switch (panel.edge()) {
            case TOP -> ShellSurface.TOP;
            case BOTTOM -> ShellSurface.BOTTOM;
            case LEFT -> ShellSurface.LEFT;
            case RIGHT -> ShellSurface.RIGHT;
        };
        final var margins = switch (panel.edge()) {
            case TOP -> new ShellSurface.Margins(side, edge, side, 0);
            case BOTTOM -> new ShellSurface.Margins(side, 0, side, edge);
            case LEFT -> new ShellSurface.Margins(edge, side, 0, side);
            case RIGHT -> new ShellSurface.Margins(0, side, edge, side);
        };
        return new PanelGeometry(panel, vertical ? thickness : length, vertical ? length : thickness,
                anchor | alignment, margins, side == 0 && edge == 0 && length == availableLength);
    }

    ShellSurface.Placement placement() {
        final boolean fill = panel.style().length() == ShellAppearance.Width.FILL;
        return new ShellSurface.Placement(ShellSurface.Reference.PANEL, anchors,
                fill && !panel.edge().vertical() ? 0 : width,
                fill && panel.edge().vertical() ? 0 : height, margins);
    }

    int thickness() { return panel.edge().vertical() ? width : height; }

    ShellReservation.Edge reservationEdge() {
        return switch (panel.edge()) {
            case TOP -> ShellReservation.Edge.TOP;
            case BOTTOM -> ShellReservation.Edge.BOTTOM;
            case LEFT -> ShellReservation.Edge.LEFT;
            case RIGHT -> ShellReservation.Edge.RIGHT;
        };
    }

    ShellSurface.Margins paintExtension(DesktopViewport viewport) {
        if (!extendInset) return ShellSurface.Margins.NONE;
        return switch (panel.edge()) {
            case TOP -> new ShellSurface.Margins(0, viewport.insetTop(), 0, 0);
            case BOTTOM -> new ShellSurface.Margins(0, 0, 0, viewport.insetBottom());
            case LEFT -> new ShellSurface.Margins(viewport.insetLeft(), 0, 0, 0);
            case RIGHT -> new ShellSurface.Margins(0, 0, viewport.insetRight(), 0);
        };
    }

    static ShellBounds reveal(ShellBounds output, ShellBounds surface, ShellPanel.Edge edge, int thickness) {
        final int depth = Math.max(1, Math.min(thickness, edge.vertical() ? output.width() : output.height()));
        final ShellBounds span = surface.intersect(output);
        return switch (edge) {
            case TOP -> new ShellBounds(span.left(), output.top(), span.right(), output.top() + depth);
            case BOTTOM -> new ShellBounds(span.left(), output.bottom() - depth, span.right(), output.bottom());
            case LEFT -> new ShellBounds(output.left(), span.top(), output.left() + depth, span.bottom());
            case RIGHT -> new ShellBounds(output.right() - depth, span.top(), output.right(), span.bottom());
        };
    }

    static ShellBounds presented(ShellBounds output, ShellBounds surface, ShellPanel.Edge panelEdge,
            boolean visible, boolean edgeHidden, int edgeThickness) {
        if (!visible) return new ShellBounds(0, 0, 0, 0);
        return edgeHidden ? reveal(output, surface, panelEdge, edgeThickness) : surface;
    }

    static int paintAlpha(boolean visible, boolean edgeHidden) { return visible && !edgeHidden ? 255 : 0; }

    static ShellBounds expanded(ShellBounds paint, ShellBounds output, int overflow) {
        int margin = Math.max(0, overflow);
        return new ShellBounds(Math.max(output.left(), paint.left() - margin), Math.max(output.top(), paint.top() - margin),
                Math.min(output.right(), paint.right() + margin), Math.min(output.bottom(), paint.bottom() + margin));
    }
}
