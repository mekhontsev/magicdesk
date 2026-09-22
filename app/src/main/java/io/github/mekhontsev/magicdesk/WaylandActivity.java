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
        BuiltInWindowRegistry.PresentationSource, BuiltInWindowRegistry.CloseHandler {
    private static final String SESSION = "wayland_session", WINDOW = "wayland_window";
    private WaylandSessions.Session session;
    private WaylandSession.Output output;
    private HostedSurfaceView surface;
    private TextView status;
    private long window;
    private volatile BuiltInWindowRegistry.Presentation presentation;

    static Intent windowIntent(Context context, WaylandSessions.Session session, long window) {
        return new Intent(context, WaylandActivity.class).putExtra(SESSION, session.id()).putExtra(WINDOW, window)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.wayland_title, R.drawable.ic_show_desktop);
        session = WaylandSessions.find(getIntent().getStringExtra(SESSION));
        window = getIntent().getLongExtra(WINDOW, 0);
        DesktopUiFactory ui = new DesktopUiFactory(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        SystemBarInsets.addToPadding(root, true, this);
        status = new TextView(this);
        status.setTextColor(DesktopUiFactory.COLOR_MUTED);
        status.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        root.addView(status);
        surface = new HostedSurfaceView(this);
        surface.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (output == null && right > left && bottom > top) changed();
        });
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (session != null && window > 0) {
            session.listen(this);
            session.host(getTaskId(), window);
            session.presentation.host(this);
        }
        changed();
    }

    @Override public void changed() {
        if (isDestroyed() || isFinishing()) return;
        if (session == null || window <= 0 || session.stopped() || !session.containsWindow(window)) {
            finishAndRemoveTask(); return;
        }
        var current = session.windows().stream().filter(item -> item.id() == window).findFirst().orElseThrow();
        String title = current.title().isBlank() ? session.name : current.title();
        if (presentation == null || !presentation.title().equals(title)) {
            presentation = new BuiltInWindowRegistry.Presentation(title, null);
            setTitle(title);
            DesktopTaskDescription.apply(this, title, R.drawable.ic_show_desktop);
            DesktopRuntimeBridge.refreshTaskPresentations();
        }
        status.setText(session.error());
        status.setVisibility(session.error().isEmpty() ? View.GONE : View.VISIBLE);
        if (current.mapped() && output == null && surface.getWidth() > 0 && surface.getHeight() > 0) {
            output = session.openOutput(window, surface.getWidth(), surface.getHeight());
            surface.bind(new WaylandSurfaceOutput(output));
            surface.requestFocus();
        } else if (!current.mapped() && output != null) { surface.release(); output = null; }
    }

    @Override public void frame(long id, int width, int height) {
        if (!isFinishing() && !isDestroyed() && output != null && output.id == id) surface.frame(width, height);
    }
    @Override public BuiltInWindowRegistry.Presentation taskPresentation() { return presentation; }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (output != null) output.focus(focused);
        if (focused && session != null) session.presentation.host(this);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (session != null) session.presentation.host(this);
    }
    @Override public void requestClose(boolean force) {
        if (session != null && session.ready() && session.containsWindow(window)) session.closeWindow(window, force);
        else finishAndRemoveTask();
    }
    @Override public BuiltInWindowRegistry.ForceCloseAction forceCloseAction() {
        return new BuiltInWindowRegistry.ForceCloseAction(R.string.action_force_stop, getString(R.string.graphics_force_stop_client));
    }
    @Override public void onDestroy() {
        boolean request = isFinishing() && session != null && session.ready() && session.containsWindow(window);
        if (request) session.closeWindow(window, false);
        if (surface != null) surface.release();
        output = null;
        if (session != null) {
            session.unlisten(this);
            session.releaseHost(getTaskId());
            session.presentation.hostRemoved(this, window, request);
        }
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
