package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.mekhontsev.magicdesk.wayland.WaylandSession;

/** An Android host borrows one Wayland toplevel; it does not own the compositor. */
public final class WaylandActivity extends Activity implements WaylandSessions.Listener,
        BuiltInWindowRegistry.PresentationSource, BuiltInWindowRegistry.CloseHandler, BuiltInWindowRegistry.ImmersiveSource,
        BuiltInWindowRegistry.ApplicationSource, BuiltInWindowRegistry.DesktopPresentationListener, HostedWindowPresentation.ContentHost {
    @Override public long hostedWindowId() { return window; }
    @Override public HostedSurfaceView hostedSurface() { return surface; }
    @Override public boolean wholeDesktopViewer() { return session != null && session.desktop; }
    static final String SESSION = "wayland_session";
    private static final String WINDOW = "wayland_window";
    private WaylandSessions.Session session;
    private WaylandHostBinding binding;
    private HostedSurfaceView surface;
    private HostedContentLayout content;
    private TextView status;
    private long window;
    private volatile BuiltInWindowRegistry.Presentation presentation;
    private volatile AppReference application;
    private RecentApplicationStore.Entry identityRecipe;
    private boolean pendingLaunch;
    private boolean detached;

    static Intent windowIntent(Context context, WaylandSessions.Session session, long window) {
        return BuiltInWindowIdentity.bind(new Intent(context, WaylandActivity.class)
                .putExtra(SESSION, session.id()).putExtra(WINDOW, window)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                GraphicalApplicationLaunch.reference(context, session.windowRecipe(window)));
    }

    @Override public void onCreate(Bundle state) {
        HostedStartupWindow.prepare(this);
        super.onCreate(state);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.wayland_title, R.drawable.ic_show_desktop);
        String sessionId = state == null ? getIntent().getStringExtra(SESSION) : state.getString(SESSION);
        session = WaylandSessions.find(sessionId);
        window = state == null ? getIntent().getLongExtra(WINDOW, 0) : state.getLong(WINDOW);
        DesktopUiFactory ui = new DesktopUiFactory(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        UiAppearance.background(root, UiColor.BACKGROUND);
        SystemBarInsets.addToPadding(root, true, this);
        status = new TextView(this);
        UiAppearance.text(status, UiColor.MUTED);
        status.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        root.addView(status);
        surface = new HostedSurfaceView(this);
        surface.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (session != null && binding == null && right > left && bottom > top) changed();
        });
        content = new HostedContentLayout(this, surface);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        HostedStartupWindow.layout(this);
        if (session != null) {
            pendingLaunch = window == 0 && (session.recipe() != null || session.desktop);
            session.listen(this);
            session.host(getTaskId(), window);
            session.presentation.host(this);
        }
        changed();
    }

    @Override public void changed() {
        if (isDestroyed() || isFinishing()) return;
        if (session == null || session.presentation.isClosed() && !session.state().equals("FAILED")) {
            finishAndRemoveTask(); return;
        }
        if (pendingLaunch) {
            window = session.windows().stream().filter(WaylandSession.Window::mapped)
                    .mapToLong(WaylandSession.Window::id).findFirst().orElse(0);
            if (window == 0) {
                status.setVisibility(View.VISIBLE);
                status.setText(!session.error().isEmpty() ? session.error() : getString(R.string.graphics_waiting_application));
                present(session.name);
                return;
            }
            pendingLaunch = false;
            session.host(getTaskId(), window);
        }
        var currentRecipe = HostedStartupWindow.temporary(this) ? null : session.windowRecipe(window);
        if (currentRecipe != identityRecipe) {
            identityRecipe = currentRecipe;
            application = GraphicalApplicationLaunch.reference(this, currentRecipe);
            DesktopRuntimeBridge.refreshTaskPresentations();
        }
        if (session.stopped() || window <= 0 || !session.containsWindow(window)) { finishAndRemoveTask(); return; }
        var current = session.windows().stream().filter(item -> item.id() == window).findFirst().orElseThrow();
        if (HostedStartupWindow.temporary(this) && !current.mapped()) { detachHostedWindow(); return; }
        content.constraints(current.constraints(), session.unitScale(this));
        String title = current.title().isBlank() ? session.name : current.title();
        present(title);
        status.setText(session.error());
        status.setVisibility(session.error().isEmpty() ? View.GONE : View.VISIBLE);
        if (current.mapped() && binding == null && surface.getWidth() > 0 && surface.getHeight() > 0) {
            binding = new WaylandHostBinding(this, surface, session, window);
        } else if (!current.mapped() && binding != null) { binding.close(); binding = null; }
        if (binding != null) binding.refresh();
    }

    private void present(String title) {
        var recipe = session.recipe();
        var icon = DesktopApplicationIconResolver.hostedIcon(null, recipe);
        if (presentation != null && presentation.title().equals(title) && presentation.icon() == icon) return;
        presentation = new BuiltInWindowRegistry.Presentation(title, icon);
        setTitle(title);
        if (icon == null) DesktopTaskDescription.apply(this, title, R.drawable.ic_show_desktop);
        else DesktopTaskDescription.apply(this, title, icon);
        DesktopRuntimeBridge.refreshTaskPresentations();
    }

    @Override public void frame(long id, int width, int height) {
        if (!isFinishing() && !isDestroyed() && binding != null) binding.frame(id, width, height);
    }
    @Override public void geometryChanged(long id) { if (id == window && binding != null) binding.geometryChanged(); }
    @Override public void textInputChanged(long output) { if (binding != null) binding.textInputChanged(output); }
    @Override public void windowGesture(long id, io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture gesture) {
        if (id == window && binding != null) binding.windowGesture(gesture);
    }
    @Override public void cursor(long output, android.graphics.Bitmap image, int hotspotX, int hotspotY, boolean hidden) {
        if (binding != null) binding.cursor(output, image, hotspotX, hotspotY, hidden);
    }
    @Override public void desktopPresentationChanged() { if (binding != null) binding.refresh(); }
    @Override public BuiltInWindowRegistry.Presentation taskPresentation() { return presentation; }
    @Override protected void onStart() { super.onStart(); if (binding != null) binding.visible(true); }
    @Override protected void onStop() { if (binding != null) binding.visible(false); super.onStop(); }
    @Override public BuiltInWindowRegistry.ImmersiveRequest immersiveRequest() {
        return binding == null ? null : binding.immersiveRequest();
    }
    @Override public void onImmersiveRejected() { if (binding != null) binding.rejectImmersive(); }
    @Override public AppReference windowApplication() { return application; }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (session != null) state.putString(SESSION, session.id());
        state.putLong(WINDOW, window);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (binding != null) binding.focusChanged();
        if (focused && session != null) {
            session.presentation.host(this);
            changed();
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        HostedStartupWindow.layout(this);
        if (session != null) {
            session.presentation.host(this);
            changed();
        }
    }
    @Override public void requestClose(boolean force) {
        if (session != null && session.desktop) { finishAndRemoveTask(); return; }
        if (pendingLaunch && session != null) { session.close(); finishAndRemoveTask(); return; }
        if (session != null && session.ready() && session.containsWindow(window)) session.closeWindow(window, force);
        else finishAndRemoveTask();
    }
    @Override public BuiltInWindowRegistry.ForceCloseAction forceCloseAction() {
        if (session != null && session.desktop) return null;
        return new BuiltInWindowRegistry.ForceCloseAction(R.string.action_force_stop, getString(R.string.graphics_force_stop_client));
    }
    @Override public void detachHostedWindow() { detached = true; finishAndRemoveTask(); }
    @Override public void onDestroy() {
        if (isFinishing() && pendingLaunch && session != null && !session.desktop) session.close();
        boolean request = isFinishing() && !detached && session != null && !session.desktop && session.ready() && session.containsWindow(window);
        if (request) session.closeWindow(window, false);
        if (binding != null) binding.close();
        else if (surface != null) surface.release();
        binding = null;
        if (session != null) {
            session.unlisten(this);
            session.releaseHost(getTaskId());
            session.presentation.hostRemoved(this, window, request);
        }
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
