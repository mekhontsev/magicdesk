package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.wayland.WaylandShellSurface;
import java.util.List;

/** Converts a protocol owner's surface coordinates into one explicitly supplied layout scope. */
final class WaylandShellLayout implements AutoCloseable {
    record Configuration(long id, long revision, ShellBounds bounds) { }

    private final ShellLayoutScope mScope;
    private final ShellLayoutScope.Binding mBinding;
    private List<WaylandShellSurface> mSurfaces = List.of();
    private int mDensity = 160;

    WaylandShellLayout(final ShellLayoutScope scope) {
        mScope = java.util.Objects.requireNonNull(scope);
        if (scope.snapshot() == null) throw new IllegalStateException("Shell layout viewport is not ready");
        mBinding = scope.bind();
    }

    boolean isClosed() { return mBinding.isClosed(); }

    void update(final List<WaylandShellSurface> surfaces, final int densityDpi) {
        if (densityDpi <= 0) throw new IllegalArgumentException("Shell output density must be positive");
        final List<WaylandShellSurface> next = List.copyOf(surfaces);
        // Publish adapter state before the scope notifies geometry consumers, rolling back on validation failure.
        final var previous = mSurfaces;
        final var previousLayout = mScope.snapshot();
        final int previousDensity = mDensity;
        final var intents = next.stream().map(surface -> intent(surface, densityDpi)).toList();
        mSurfaces = next;
        mDensity = densityDpi;
        try { mBinding.commit(intents); }
        catch (RuntimeException error) {
            if (mScope.snapshot() == previousLayout) { mSurfaces = previous; mDensity = previousDensity; }
            throw error;
        }
    }

    ShellBounds output() {
        final ShellBounds pixels = mScope.snapshot().output();
        return new ShellBounds(0, 0, units(pixels.width()), units(pixels.height()));
    }

    ShellLayout.Surface surface(final long id) { return mBinding.surface(Long.toString(id)); }

    List<Configuration> configurations() {
        if (isClosed()) return List.of();
        final ShellBounds origin = mScope.snapshot().output();
        final var result = new java.util.ArrayList<Configuration>();
        for (var state : mSurfaces) {
            if (!state.mapped() && !state.configureNeeded()) continue;
            final ShellBounds content = surface(state.id()).content();
            final int left = (int) ((long) (content.left() - origin.left()) * 160 / mDensity);
            final int top = (int) ((long) (content.top() - origin.top()) * 160 / mDensity);
            result.add(new Configuration(state.id(), state.revision(), new ShellBounds(left, top,
                    Math.max(left + 1, units(content.right() - origin.left())),
                    Math.max(top + 1, units(content.bottom() - origin.top())))));
        }
        return List.copyOf(result);
    }

    private int units(final int pixels) {
        return (int) Math.min(Integer.MAX_VALUE, ((long) pixels * 160 + mDensity - 1) / mDensity);
    }

    private static int pixels(final long units, final int density) {
        final double value = units * (density / 160.0);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(value)));
    }

    private static int size(final long units, final int density) {
        return units == 0 ? 0 : Math.max(1, pixels(units, density));
    }

    private static ShellSurface intent(final WaylandShellSurface state, final int density) {
        final int anchors = state.anchors();
        final ShellReservation.Edge edge = switch (anchors) {
            case WaylandShellSurface.LEFT, WaylandShellSurface.LEFT | WaylandShellSurface.TOP | WaylandShellSurface.BOTTOM -> ShellReservation.Edge.LEFT;
            case WaylandShellSurface.TOP, WaylandShellSurface.TOP | WaylandShellSurface.LEFT | WaylandShellSurface.RIGHT -> ShellReservation.Edge.TOP;
            case WaylandShellSurface.RIGHT, WaylandShellSurface.RIGHT | WaylandShellSurface.TOP | WaylandShellSurface.BOTTOM -> ShellReservation.Edge.RIGHT;
            case WaylandShellSurface.BOTTOM, WaylandShellSurface.BOTTOM | WaylandShellSurface.LEFT | WaylandShellSurface.RIGHT -> ShellReservation.Edge.BOTTOM;
            default -> null;
        };
        final var reservations = state.exclusiveZone() > 0 && edge != null
                ? List.of(ShellReservation.exclusive(edge, size(state.exclusiveZone(), density), true))
                : List.<ShellReservation>of();
        return new ShellSurface(Long.toString(state.id()), state.mapped(), switch (state.layer()) {
            case BACKGROUND -> ShellSurface.Layer.BACKGROUND;
            case BOTTOM -> ShellSurface.Layer.BOTTOM;
            case TOP -> ShellSurface.Layer.TOP;
            case OVERLAY -> ShellSurface.Layer.OVERLAY;
        }, switch (state.keyboard()) {
            case NONE -> ShellSurface.Keyboard.NONE;
            case ON_DEMAND -> ShellSurface.Keyboard.ON_DEMAND;
            case EXCLUSIVE -> ShellSurface.Keyboard.EXCLUSIVE;
        }, new ShellSurface.Placement(state.exclusiveZone() < 0
                ? ShellSurface.Reference.OUTPUT : ShellSurface.Reference.AVAILABLE, anchors,
                size(state.width(), density), size(state.height(), density),
                new ShellSurface.Margins(pixels(state.marginLeft(), density), pixels(state.marginTop(), density),
                        pixels(state.marginRight(), density), pixels(state.marginBottom(), density))),
                ShellSurface.Margins.NONE, ShellSurface.Input.CONTENT, reservations);
    }

    @Override public void close() {
        mSurfaces = List.of();
        mBinding.close();
    }
}
