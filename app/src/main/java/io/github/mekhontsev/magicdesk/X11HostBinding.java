package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import io.github.mekhontsev.magicdesk.x11.X11Session;
import io.github.mekhontsev.magicdesk.x11.X11WindowManagement;

/** One Android host's borrowed resources. Session, density, clipboard and fullscreen keep their own policies. */
final class X11HostBinding implements X11Sessions.Listener {
    private final Activity activity;
    private final HostedSurfaceView surface;
    private final X11Sessions.Session session;
    private final boolean application;
    private final Runnable changed;
    private X11Session.Output output;
    private HostedContentExchange exchange;
    private HostedContentExchange dependentExchange;
    private HostedFamilyWindows family;
    private X11Session.Output dependentOutput;
    private volatile HostedFullscreen fullscreen;
    private X11WindowManagement.Request fullscreenRequest;
    private long window;
    private boolean closed;

    X11HostBinding(Activity activity, HostedSurfaceView surface, X11Sessions.Session session,
            long window, boolean application, Runnable changed) {
        this.activity = activity;
        this.surface = surface;
        this.session = session;
        this.window = window;
        this.application = application;
        this.changed = changed;
        session.listen(this);
        session.host(activity.getTaskId(), window, activity.hasWindowFocus());
        updateDensity();
    }

    void refresh(long window, boolean present) {
        if (closed) return;
        if (this.window != window) {
            releaseFullscreen();
            releaseOutput();
        }
        this.window = window;
        session.host(activity.getTaskId(), window, activity.hasWindowFocus());
        if (present && output == null) {
            try {
                output = session.openOutput(window);
                surface.bind(new X11SurfaceOutput(output));
                exchange = new HostedContentExchange(activity, surface, new X11ContentExchange(activity, session, output));
                if (window != 0) family = new HostedFamilyWindows(activity, surface, new HostedFamilyWindows.Backend() {
                    @Override public HostedShellOutput borrow(java.util.function.Consumer<HostedFamilyGeometry> changed,
                            java.util.function.Consumer<Throwable> failed) {
                        dependentOutput = output.borrowDependents(geometry -> changed.accept(new HostedFamilyGeometry(
                                geometry.width(), geometry.height(), bounds(geometry.paint()), geometry.inputComplete(),
                                geometry.input().stream().map(X11HostBinding::bounds).toList())));
                        return new X11SurfaceOutput(dependentOutput);
                    }
                    @Override public void mounted(HostedSurfaceView view) {
                        dependentExchange = new HostedContentExchange(activity, view,
                                new X11ContentExchange(activity, session, dependentOutput));
                        updateExchangeFocus();
                    }
                    @Override public void unmounted() {
                        if (dependentExchange != null) dependentExchange.close();
                        dependentExchange = null;
                    }
                    @Override public void released() { dependentOutput = null; }
                    @Override public void focus(boolean focused, boolean dependent) {
                        if (output == null) return;
                        if (focused) output.focus();
                        else if (dependentOutput != null) output.blurFamily();
                        session.host(activity.getTaskId(), X11HostBinding.this.window, focused);
                        updateExchangeFocus();
                    }
                });
                surface.requestFocus();
            } catch (RuntimeException error) {
                releaseOutput();
                throw error;
            }
        } else if (!present && output != null) releaseOutput();
        updateExchangeFocus();
        updateFullscreen();
        if (family != null) family.refresh();
    }

    private static ShellBounds bounds(io.github.mekhontsev.magicdesk.x11.X11ShellSurface.Rect rect) {
        return new ShellBounds(rect.left(), rect.top(), rect.right(), rect.bottom());
    }

    private void updateFullscreen() {
        X11Session.Window info = window == 0 ? null
                : session.windows().stream().filter(item -> item.id() == window).findFirst().orElse(null);
        if (output == null || info == null || !info.management().managed() || !session.claimFullscreen(window, this)) {
            releaseFullscreen();
            return;
        }
        if (fullscreen == null) fullscreen = new HostedFullscreen(activity, surface,
                actual -> session.confirmFullscreen(window, this, fullscreenRequest, new X11WindowManagement.State(actual)));
        X11WindowManagement.Request request = info.management().request();
        if (fullscreenRequest == null || fullscreenRequest.serial() != request.serial()) {
            fullscreenRequest = request;
            fullscreen.request(request.fullscreen());
        } else fullscreen.changed();
    }

    BuiltInWindowRegistry.ImmersiveRequest immersiveRequest() {
        HostedFullscreen current = fullscreen;
        return current == null ? null : current.snapshot();
    }

    void rejectImmersive() { if (!closed && fullscreen != null) fullscreen.reject(); }

    private void releaseFullscreen() {
        if (fullscreen != null) fullscreen.close();
        fullscreen = null;
        fullscreenRequest = null;
        session.releaseFullscreen(this);
    }

    void updateDensity() {
        if (!closed) session.hostDensity(this,
                activity.getResources().getConfiguration().densityDpi, activity.hasWindowFocus());
    }

    void focusChanged(boolean focused) {
        if (closed) return;
        updateDensity();
        if (family != null) family.focusChanged(); else session.host(activity.getTaskId(), window, focused);
    }

    void presentationChanged() {
        if (closed) return;
        if (fullscreen != null) fullscreen.changed();
        if (family != null) family.refresh();
    }
    void updateExchangeFocus() {
        if (closed) return;
        if (exchange != null) exchange.focus(activity.hasWindowFocus());
        if (dependentExchange != null) dependentExchange.focus(family != null && family.focused() && !activity.hasWindowFocus());
    }

    private void releaseExchange() {
        if (exchange != null) exchange.close();
        exchange = null;
    }

    private void releaseOutput() {
        if (family != null) family.close();
        family = null;
        dependentOutput = null;
        releaseExchange();
        surface.release();
        // Also covers a bind that failed before the SurfaceView could take ownership.
        if (output != null) output.close();
        output = null;
    }

    @Override public void onChanged() { if (!closed) changed.run(); }

    @Override public X11Sessions.Host inspectHost() {
        if (closed || output == null || activity.isDestroyed() || activity.isFinishing()) return null;
        return new X11Sessions.Host(activity.getTaskId(), activity.getDisplay() == null ? -1 : activity.getDisplay().getDisplayId(),
                window, activity.hasWindowFocus(), surface.geometry());
    }

    @Override public void onFrame(X11Session.Output source, int width, int height, boolean available) {
        if (!closed && source == output) surface.frame(available ? width : 0, available ? height : 0);
    }

    @Override public void onCursor(X11Session.Output source, X11Session.Cursor cursor) {
        if (!closed && source == output)
            surface.cursor(cursor.image(), cursor.hotspotX(), cursor.hotspotY(), cursor.hidden());
        else if (!closed && source == dependentOutput && family != null)
            family.cursor(cursor.image(), cursor.hotspotX(), cursor.hotspotY(), cursor.hidden());
    }

    boolean requestClose(boolean force) {
        if (closed) return false;
        if (window != 0 && session.windows().stream().anyMatch(item -> item.id() == window)) {
            session.closeWindow(window, force);
            return true;
        }
        if (force || (application && window == 0)) session.close();
        return false;
    }

    void close(boolean finishing) {
        if (closed) return;
        boolean clientCloseRequested = false;
        // Direct Android removal cannot be vetoed; the session can replace a
        // removed client host while its close confirmation is still pending.
        if (finishing) {
            try { clientCloseRequested = requestClose(false); }
            catch (IllegalStateException ignored) { /* Disconnected session has no live close channel. */ }
        }
        closed = true;
        releaseFullscreen();
        releaseExchange();
        session.unlisten(this);
        session.releaseDensity(this);
        session.releaseHost(activity.getTaskId());
        releaseOutput();
        session.presentation.hostRemoved(activity, window, clientCloseRequested);
    }
}
