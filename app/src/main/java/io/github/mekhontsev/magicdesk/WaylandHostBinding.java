package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import io.github.mekhontsev.magicdesk.wayland.WaylandSession;
import io.github.mekhontsev.magicdesk.wayland.WaylandViewGeometry;
import java.util.function.Consumer;

/** Android resources borrowed by one Wayland application host, independently of its server. */
final class WaylandHostBinding implements AutoCloseable {
    private final HostedSurfaceView surface;
    private final WaylandSessions.Session session;
    private final long window;
    private final WaylandSession.Output output;
    private final HostedFamilyWindows family;
    private WaylandSession.Output dependents;
    private Consumer<HostedFamilyGeometry> geometry;
    private WaylandViewGeometry published;
    private int publishedWidth, publishedHeight;
    private boolean closed;

    WaylandHostBinding(Activity activity, HostedSurfaceView surface, WaylandSessions.Session session, long window) {
        this.surface = surface;
        this.session = session;
        this.window = window;
        output = session.openOutput(window, surface.getWidth(), surface.getHeight());
        surface.bind(new WaylandSurfaceOutput(output));
        family = new HostedFamilyWindows(activity, surface, new HostedFamilyWindows.Backend() {
            @Override public HostedShellOutput borrow(Consumer<HostedFamilyGeometry> changed, Consumer<Throwable> failed) {
                geometry = changed;
                published = null;
                dependents = output.borrowDependents(failed);
                // Cached protocol geometry is published after the borrow handle has reached its owner.
                surface.post(WaylandHostBinding.this::geometryChanged);
                return new WaylandSurfaceOutput(dependents);
            }
            @Override public void focus(boolean focused, boolean dependent) {
                if (focused) (dependent && dependents != null ? dependents : output).focus(true);
                else {
                    output.focus(false);
                    if (dependents != null) dependents.focus(false);
                }
            }
            @Override public void released() { dependents = null; geometry = null; published = null; }
        });
        surface.requestFocus();
        family.refresh();
    }

    void refresh() { if (!closed) { family.refresh(); geometryChanged(); } }
    void focusChanged() { if (!closed) family.focusChanged(); }
    void frame(long id, int width, int height) { if (!closed && id == output.id) surface.frame(width, height); }
    void geometryChanged() {
        if (closed || geometry == null) return;
        var next = session.dependentGeometry(window);
        var owner = session.windows().stream().filter(item -> item.id() == window).findFirst().orElse(null);
        if (next == null || owner == null) return;
        if (next == published && owner.width() == publishedWidth && owner.height() == publishedHeight) return;
        published = next;
        publishedWidth = owner.width(); publishedHeight = owner.height();
        geometry.accept(new HostedFamilyGeometry(owner.width(), owner.height(), bounds(next.paint()),
                next.inputComplete(), next.input().stream().map(WaylandHostBinding::bounds).toList()));
    }
    private static ShellBounds bounds(WaylandViewGeometry.Rect rect) {
        return new ShellBounds(rect.left(), rect.top(), rect.right(), rect.bottom());
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        family.close();
        surface.release();
        output.close();
        geometry = null;
    }
}
