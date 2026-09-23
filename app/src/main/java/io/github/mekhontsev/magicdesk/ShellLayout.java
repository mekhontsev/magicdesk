package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One workspace or nested-desktop coordinate scope. Its owner commits mapped
 * surfaces, clears their mapped state on unmap and omits them on disconnect.
 * No display IDs or global registry.
 * Mutations belong to the owner's event thread; snapshots are immutable.
 */
final class ShellLayout {
    record Surface(ShellSurface request, ShellBounds content, ShellBounds paint,
            ShellBounds input) { }
    record Exclusion(String owner, ShellReservation.Edge edge, ShellBounds bounds,
            boolean windows) { }
    record Snapshot(ShellBounds output, ShellBounds content, ShellBounds workArea,
            ShellBounds panelArea, Map<String, Surface> surfaces, List<Exclusion> exclusions) {
        Snapshot {
            surfaces = Collections.unmodifiableMap(new LinkedHashMap<>(surfaces));
            exclusions = List.copyOf(exclusions);
        }
    }

    private List<ShellSurface> mSurfaces = List.of();
    private ShellBounds mOutput;
    private ShellBounds mContent;
    private volatile Snapshot mSnapshot;

    void commit(final ShellBounds output, final ShellBounds content,
            final List<ShellSurface> surfaces) {
        if (output.isEmpty() || content.isEmpty() || !output.intersect(content).equals(content)) {
            throw new IllegalArgumentException("Content must be inside the output");
        }
        final java.util.Set<String> identities = new java.util.HashSet<>();
        for (ShellSurface surface : surfaces) {
            if (!identities.add(surface.id())) {
                throw new IllegalArgumentException("Duplicate shell surface identity");
            }
        }
        if (!output.equals(mOutput) || !content.equals(mContent) || !surfaces.equals(mSurfaces)) {
            mOutput = output;
            mContent = content;
            mSurfaces = List.copyOf(surfaces);
            resolve();
        }
    }

    void clear() {
        if (!mSurfaces.isEmpty()) {
            mSurfaces = List.of();
            resolve();
        }
    }

    Snapshot snapshot() { return mSnapshot; }

    private void resolve() {
        final List<Exclusion> exclusions = new ArrayList<>();
        final Map<String, Surface> resolved = new LinkedHashMap<>();
        final List<ShellSurface> ordered = new ArrayList<>(mSurfaces);
        // Exclusive clients precede ordinary clients. Within a layer retain the
        // owner's insertion order, including when a surface commits new state.
        ordered.sort(Comparator.comparing((ShellSurface surface) ->
                !surface.mapped() || surface.reservations().stream().noneMatch(ShellReservation::windows))
                .thenComparing(Comparator.comparing(ShellSurface::layer).reversed()));
        for (ShellSurface surface : ordered) {
            for (ShellReservation reservation : surface.reservations()) {
                if (surface.mapped() && reservation.origin() == ShellReservation.Origin.OUTPUT) {
                    addExclusion(exclusions, surface, reservation, mOutput);
                }
            }
        }
        for (ShellSurface surface : ordered) {
            final ShellBounds frame = switch (surface.placement().reference()) {
                case OUTPUT -> mOutput;
                case CONTENT -> mContent;
                case AVAILABLE -> available(exclusions, true);
            };
            final ShellBounds bounds = place(surface.placement(), frame);
            final ShellSurface.Margins extension = surface.paintExtension();
            final ShellBounds paint = new ShellBounds(
                    clamp((long) bounds.left() - extension.left(), mOutput.left(), bounds.right()),
                    clamp((long) bounds.top() - extension.top(), mOutput.top(), bounds.bottom()),
                    clamp((long) bounds.right() + extension.right(), bounds.left(), mOutput.right()),
                    clamp((long) bounds.bottom() + extension.bottom(), bounds.top(), mOutput.bottom()));
            final ShellBounds input = switch (surface.mapped() ? surface.input() : ShellSurface.Input.NONE) {
                case NONE -> new ShellBounds(bounds.left(), bounds.top(), bounds.left(), bounds.top());
                case CONTENT -> bounds;
                case PAINT -> paint;
            };
            resolved.put(surface.id(), new Surface(surface, bounds, paint, input));
            for (ShellReservation reservation : surface.reservations()) {
                if (surface.mapped() && reservation.origin() == ShellReservation.Origin.PLACEMENT) {
                    addExclusion(exclusions, surface, reservation, frame);
                }
            }
        }
        mSnapshot = new Snapshot(mOutput, mContent, available(exclusions, true),
                available(exclusions, false), resolved, exclusions);
    }

    private void addExclusion(final List<Exclusion> exclusions, final ShellSurface surface,
            final ShellReservation reservation, final ShellBounds frame) {
        if (reservation.depth() == 0) {
            return;
        }
        final boolean horizontal = reservation.edge() == ShellReservation.Edge.TOP
                || reservation.edge() == ShellReservation.Edge.BOTTOM;
        final boolean absolute = reservation.origin() == ShellReservation.Origin.OUTPUT;
        final int start = absolute ? reservation.start() : horizontal ? frame.left() : frame.top();
        final int end = absolute ? reservation.end() : horizontal ? frame.right() : frame.bottom();
        final ShellSurface.Margins margins = surface.placement().margins();
        final int margin = absolute ? 0 : switch (reservation.edge()) {
            case LEFT -> margins.left();
            case TOP -> margins.top();
            case RIGHT -> margins.right();
            case BOTTOM -> margins.bottom();
        };
        final long depth = Math.max(0L, (long) reservation.depth() + margin);
        final ShellBounds bounds = switch (reservation.edge()) {
            case LEFT -> new ShellBounds(frame.left(), start,
                    clamp((long) frame.left() + depth, frame.left(), frame.right()), end);
            case TOP -> new ShellBounds(start, frame.top(), end,
                    clamp((long) frame.top() + depth, frame.top(), frame.bottom()));
            case RIGHT -> new ShellBounds(
                    clamp((long) frame.right() - depth, frame.left(), frame.right()), start,
                    frame.right(), end);
            case BOTTOM -> new ShellBounds(start,
                    clamp((long) frame.bottom() - depth, frame.top(), frame.bottom()), end,
                    frame.bottom());
        };
        final ShellBounds clipped = bounds.intersect(mContent);
        if (!clipped.isEmpty()) {
            exclusions.add(new Exclusion(surface.id(), reservation.edge(), clipped,
                    reservation.windows()));
        }
    }

    private ShellBounds available(final List<Exclusion> exclusions, final boolean windowsOnly) {
        int left = mContent.left();
        int top = mContent.top();
        int right = mContent.right();
        int bottom = mContent.bottom();
        for (Exclusion exclusion : exclusions) {
            if (windowsOnly && !exclusion.windows()) {
                continue;
            }
            final ShellBounds bounds = exclusion.bounds();
            switch (exclusion.edge()) {
                case LEFT -> left = Math.max(left, bounds.right());
                case TOP -> top = Math.max(top, bounds.bottom());
                case RIGHT -> right = Math.min(right, bounds.left());
                case BOTTOM -> bottom = Math.min(bottom, bounds.top());
            }
        }
        // The rectangular compatibility view is conservative. Precise partial
        // exclusions remain in the snapshot for consumers that support regions.
        left = Math.min(left, mContent.right() - 1);
        top = Math.min(top, mContent.bottom() - 1);
        return new ShellBounds(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom));
    }

    private static ShellBounds place(final ShellSurface.Placement placement, final ShellBounds frame) {
        final int anchors = placement.anchors();
        final ShellSurface.Margins margins = placement.margins();
        final boolean centerX = placement.width() > 0
                && (anchors & (ShellSurface.LEFT | ShellSurface.RIGHT)) == (ShellSurface.LEFT | ShellSurface.RIGHT);
        final boolean centerY = placement.height() > 0
                && (anchors & (ShellSurface.TOP | ShellSurface.BOTTOM)) == (ShellSurface.TOP | ShellSurface.BOTTOM);
        final int left = !centerX && (anchors & ShellSurface.LEFT) != 0 ? margins.left() : 0;
        final int right = !centerX && (anchors & ShellSurface.RIGHT) != 0 ? margins.right() : 0;
        final int top = !centerY && (anchors & ShellSurface.TOP) != 0 ? margins.top() : 0;
        final int bottom = !centerY && (anchors & ShellSurface.BOTTOM) != 0 ? margins.bottom() : 0;
        final int x1 = clamp((long) frame.left() + left, frame.left(), frame.right() - 1);
        final int x2 = clamp((long) frame.right() - right, x1 + 1, frame.right());
        final int y1 = clamp((long) frame.top() + top, frame.top(), frame.bottom() - 1);
        final int y2 = clamp((long) frame.bottom() - bottom, y1 + 1, frame.bottom());
        final int width = placement.width() == 0 ? x2 - x1 : Math.min(placement.width(), x2 - x1);
        final int height = placement.height() == 0 ? y2 - y1 : Math.min(placement.height(), y2 - y1);
        final int x = position(x1, x2, width, anchors, ShellSurface.LEFT, ShellSurface.RIGHT);
        final int y = position(y1, y2, height, anchors, ShellSurface.TOP, ShellSurface.BOTTOM);
        return new ShellBounds(x, y, x + width, y + height);
    }

    private static int position(final int start, final int end, final int size,
            final int anchors, final int first, final int last) {
        final int selected = anchors & (first | last);
        return selected == first ? start : selected == last ? end - size
                : start + (end - start - size) / 2;
    }

    private static int clamp(final long value, final int min, final int max) {
        return (int) Math.max(min, Math.min(value, max));
    }
}
