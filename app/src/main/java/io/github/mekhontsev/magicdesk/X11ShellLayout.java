package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.x11.X11ShellSurface;
import java.util.ArrayList;
import java.util.List;

/** EWMH root-pixel geometry and inclusive struts enter the shared scope without density conversion. */
final class X11ShellLayout implements AutoCloseable {
    private final ShellLayoutScope mScope;
    private final ShellLayoutScope.Binding mBinding;

    X11ShellLayout(ShellLayoutScope scope) {
        mScope = java.util.Objects.requireNonNull(scope);
        if (scope.snapshot() == null) throw new IllegalStateException("Shell layout viewport is not ready");
        mBinding = scope.bind();
    }
    boolean isClosed() { return mBinding.isClosed(); }
    void origin(String sessionId) { mBinding.origin(sessionId); }
    ShellLayout.Surface surface(long id) { return mBinding.surface(Long.toString(id)); }
    void update(List<X11ShellSurface> surfaces) {
        var output = mScope.snapshot().output();
        mBinding.commit(surfaces.stream().map(surface -> intent(surface, output)).toList());
    }
    static ShellSurface intent(X11ShellSurface state, ShellBounds output) {
        var bounds = state.bounds();
        var reservations = new ArrayList<ShellReservation>();
        var strut = state.strut();
        edge(reservations, ShellReservation.Edge.LEFT, strut, 0, 4, output.top(), output.height(), output.width());
        edge(reservations, ShellReservation.Edge.RIGHT, strut, 1, 6, output.top(), output.height(), output.width());
        edge(reservations, ShellReservation.Edge.TOP, strut, 2, 8, output.left(), output.width(), output.height());
        edge(reservations, ShellReservation.Edge.BOTTOM, strut, 3, 10, output.left(), output.width(), output.height());
        boolean dock = state.role() == X11ShellSurface.Role.DOCK;
        return new ShellSurface(Long.toString(state.id()), state.mapped(),
                dock ? ShellSurface.Layer.TOP : ShellSurface.Layer.BACKGROUND,
                dock ? ShellSurface.Keyboard.ON_DEMAND : ShellSurface.Keyboard.NONE,
                new ShellSurface.Placement(ShellSurface.Reference.OUTPUT, ShellSurface.TOP | ShellSurface.LEFT,
                        Math.max(1, bounds.right() - bounds.left()), Math.max(1, bounds.bottom() - bounds.top()),
                        new ShellSurface.Margins(bounds.left(), bounds.top(), 0, 0)),
                ShellSurface.Margins.NONE, ShellSurface.Input.CONTENT, reservations);
    }
    private static void edge(List<ShellReservation> result, ShellReservation.Edge edge, List<Long> values,
            int depthIndex, int rangeIndex, int origin, int span, int limit) {
        long depth = values.get(depthIndex), start = values.get(rangeIndex), end = values.get(rangeIndex + 1);
        if (depth == 0 || end < start || start >= span) return;
        result.add(ShellReservation.absolute(edge, (int)Math.min(depth, limit),
                origin + (int)start, origin + (int)Math.min(end + 1, span)));
    }
    @Override public void close() { mBinding.close(); }
}
