package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import io.github.mekhontsev.magicdesk.wayland.WaylandSession;
import io.github.mekhontsev.magicdesk.wayland.WaylandViewGeometry;
import java.util.function.Consumer;

/** Android resources borrowed by one Wayland application host, independently of its server. */
final class WaylandHostBinding implements AutoCloseable {
    private final HostedSurfaceView surface;
    private final Activity activity;
    private final WaylandSessions.Session session;
    private final long window;
    private final WaylandSession.Output output;
    private final HostedFamilyWindows family;
    private WaylandSession.Output dependents;
    private HostedSurfaceView dependentSurface;
    private final HostedContentExchange exchange;
    private HostedContentExchange dependentExchange;
    private Consumer<HostedFamilyGeometry> geometry;
    private WaylandViewGeometry published;
    private int publishedWidth, publishedHeight;
    private boolean closed;
    private volatile HostedFullscreen fullscreen;
    private HostedWindowCommands commands;
    private long fullscreenSerial = -1;
    private int density;

    WaylandHostBinding(Activity activity, HostedSurfaceView surface, WaylandSessions.Session session, long window) {
        this.surface = surface;
        this.activity = activity;
        this.session = session;
        this.window = window;
        output = session.openOutput(window, surface.getWidth(), surface.getHeight());
        updateDensity();
        surface.bind(new WaylandSurfaceOutput(output));
        exchange = new HostedContentExchange(activity, surface, new WaylandContentExchange(activity, session, output));
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
                updateExchangeFocus();
            }
            @Override public void mounted(HostedSurfaceView view) {
                dependentSurface = view;
                dependentExchange = new HostedContentExchange(activity, view,
                        new WaylandContentExchange(activity, session, dependents));
                updateExchangeFocus();
            }
            @Override public void unmounted() {
                if (dependentExchange != null) dependentExchange.close();
                dependentExchange = null; dependentSurface = null;
            }
            @Override public void released() { dependents = null; geometry = null; published = null; }
        });
        surface.requestFocus();
        family.refresh();
        updateExchangeFocus();
    }

    void refresh() { if (!closed) { updateDensity(); family.refresh(); geometryChanged(); updateFullscreen(); } }
    private void updateDensity() {
        int next = activity.getResources().getConfiguration().densityDpi;
        if (density == next) return;
        density = next;
        output.scale(Math.max(0.25, Math.min(8, next / 160.0)));
    }
    void focusChanged() {
        if (!closed) {
            family.focusChanged(); updateExchangeFocus(); updateFullscreen();
            if (commands != null) commands.focusChanged();
        }
    }
    void windowGesture(io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture gesture) {
        if (!closed && session.claimWindowControl(window, this) && commands != null) commands.begin(gesture);
    }
    private void updateExchangeFocus() {
        if (closed) return;
        exchange.focus(activity.hasWindowFocus());
        if (dependentExchange != null)
            dependentExchange.focus(family != null && family.focused() && !activity.hasWindowFocus());
    }
    private void updateFullscreen() {
        var info = session.windows().stream().filter(item -> item.id() == window).findFirst().orElse(null);
        if (info == null || !session.claimWindowControl(window, this)) return;
        if (commands == null) commands = new HostedWindowCommands(activity, surface,
                (serial, actual) -> session.confirmMaximized(window, this, serial, actual == io.github.mekhontsev.magicdesk.hosted.HostedMaximization.BOTH));
        commands.update(info.maximizeSerial(), info.maximized() ? io.github.mekhontsev.magicdesk.hosted.HostedMaximization.BOTH
                : io.github.mekhontsev.magicdesk.hosted.HostedMaximization.NONE, info.constraints(), Math.max(0.25f, Math.min(8, density / 160f)));
        if (fullscreen == null) fullscreen = new HostedFullscreen(activity, surface,
                actual -> session.confirmFullscreen(window, this, fullscreenSerial, actual));
        if (fullscreenSerial != info.requestSerial()) {
            fullscreenSerial = info.requestSerial();
            fullscreen.request(info.fullscreen());
        } else fullscreen.changed();
    }
    BuiltInWindowRegistry.ImmersiveRequest immersiveRequest() { return fullscreen == null ? null : fullscreen.snapshot(); }
    void rejectImmersive() { if (!closed && fullscreen != null) fullscreen.reject(); }
    void frame(long id, int width, int height) { if (!closed && id == output.id) surface.frame(width, height); }
    void textInputChanged(long id) {
        if (closed) return;
        if (id == output.id) surface.textInputChanged();
        else if (dependents != null && id == dependents.id && dependentSurface != null) dependentSurface.textInputChanged();
    }
    void cursor(long id, android.graphics.Bitmap image, int hotspotX, int hotspotY, boolean hidden) {
        if (closed) return;
        if (id == output.id) surface.cursor(image, hotspotX, hotspotY, hidden);
        else if (dependents != null && id == dependents.id) family.cursor(image, hotspotX, hotspotY, hidden);
    }
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
        if (commands != null) commands.close();
        if (fullscreen != null) fullscreen.close();
        fullscreen = null;
        session.releaseWindowControl(this);
        family.close();
        exchange.close();
        surface.release();
        output.close();
        geometry = null;
    }
}
