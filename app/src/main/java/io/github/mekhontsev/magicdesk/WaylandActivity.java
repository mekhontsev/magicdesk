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
        BuiltInWindowRegistry.PresentationSource, BuiltInWindowRegistry.CloseHandler,
        BuiltInWindowRegistry.ApplicationSource {
    static final String SESSION = "wayland_session";
    private static final String WINDOW = "wayland_window", COMMAND = "wayland_command", NAME = "wayland_name",
            DIRECTORY = "wayland_directory", BACKEND = "wayland_backend", KEYBOARD = "wayland_keyboard",
            SCOPE = "wayland_recent_scope";
    private WaylandSessions.Session session;
    private WaylandSession.Output output;
    private HostedSurfaceView surface;
    private TextView status;
    private long window;
    private volatile BuiltInWindowRegistry.Presentation presentation;
    private volatile AppReference application;
    private RecentApplicationStore.Entry identityRecipe;
    private boolean pendingLaunch;

    static Intent windowIntent(Context context, WaylandSessions.Session session, long window) {
        return BuiltInWindowIdentity.bind(new Intent(context, WaylandActivity.class)
                .putExtra(SESSION, session.id()).putExtra(WINDOW, window)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                GraphicalApplicationLaunch.reference(context, session.recipe()));
    }

    static Intent applicationIntent(Context context, DesktopLaunchRequest request, RecentLaunchScope scope) {
        return new Intent(context, WaylandActivity.class).putExtra(NAME, request.name)
                .putExtra(COMMAND, request.exec.command).putExtra(DIRECTORY, request.exec.workingDirectory)
                .putExtra(BACKEND, request.exec.backend.wireName).putExtra(KEYBOARD, request.exec.graphics.keyboardDirectory())
                .putExtra(SCOPE, scope.name()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.wayland_title, R.drawable.ic_show_desktop);
        String sessionId = state == null ? getIntent().getStringExtra(SESSION) : state.getString(SESSION);
        session = WaylandSessions.find(sessionId);
        window = state == null ? getIntent().getLongExtra(WINDOW, 0) : state.getLong(WINDOW);
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
            if (session != null && output == null && right > left && bottom > top) changed();
        });
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (state == null && sessionId == null && getIntent().hasExtra(COMMAND)) {
            try {
                DesktopExecBackend backend = DesktopExecBackend.parse(getIntent().getStringExtra(BACKEND));
                if (DesktopExecRunner.prepareBackend(this, backend) != DesktopExecRunner.StartResult.STARTED) {
                    status.setText(backend == DesktopExecBackend.TERMUX ? R.string.x11_permission_required
                            : R.string.capability_access_required);
                    return;
                }
                var recipe = DesktopEntryFile.parseRecent(getIntent().getStringExtra(GraphicalApplicationLaunch.RECIPE));
                if (recipe == null || recipe.shortcut().graphics == null
                        || recipe.shortcut().graphics.protocol() != GraphicalProtocol.WAYLAND)
                    throw new IllegalArgumentException("Invalid Wayland launch recipe");
                recipe.shortcut().graphics.requireSupported();
                RecentApplications.requireEnvironment(this, recipe);
                session = WaylandSessions.start(this, getIntent().getStringExtra(NAME), getIntent().getStringExtra(COMMAND),
                        getIntent().getStringExtra(DIRECTORY), backend, getIntent().getStringExtra(KEYBOARD), recipe);
                session.recordUse(RecentLaunchScope.valueOf(getIntent().getStringExtra(SCOPE)));
            } catch (RuntimeException error) { status.setText(ShellAccess.usefulMessage(error)); return; }
        }
        if (session != null) {
            pendingLaunch = window == 0 && session.recipe() != null;
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
        var currentRecipe = session.recipe();
        if (currentRecipe != identityRecipe) {
            identityRecipe = currentRecipe;
            application = GraphicalApplicationLaunch.reference(this, currentRecipe);
            DesktopRuntimeBridge.refreshTaskPresentations();
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
        if (session.stopped() || window <= 0 || !session.containsWindow(window)) { finishAndRemoveTask(); return; }
        var current = session.windows().stream().filter(item -> item.id() == window).findFirst().orElseThrow();
        String title = current.title().isBlank() ? session.name : current.title();
        present(title);
        status.setText(session.error());
        status.setVisibility(session.error().isEmpty() ? View.GONE : View.VISIBLE);
        if (current.mapped() && output == null && surface.getWidth() > 0 && surface.getHeight() > 0) {
            output = session.openOutput(window, surface.getWidth(), surface.getHeight());
            surface.bind(new WaylandSurfaceOutput(output));
            surface.requestFocus();
        } else if (!current.mapped() && output != null) { surface.release(); output = null; }
    }

    private void present(String title) {
        var recipe = session.recipe();
        var icon = recipe == null || recipe.termuxPackage().isEmpty() ? null
                : ApplicationCatalog.cachedTermuxIcon(recipe.shortcut().icon);
        if (presentation != null && presentation.title().equals(title) && presentation.icon() == icon) return;
        presentation = new BuiltInWindowRegistry.Presentation(title, icon);
        setTitle(title);
        if (icon == null) DesktopTaskDescription.apply(this, title, R.drawable.ic_show_desktop);
        else DesktopTaskDescription.apply(this, title, icon);
        DesktopRuntimeBridge.refreshTaskPresentations();
    }

    @Override public void frame(long id, int width, int height) {
        if (!isFinishing() && !isDestroyed() && output != null && output.id == id) surface.frame(width, height);
    }
    @Override public BuiltInWindowRegistry.Presentation taskPresentation() { return presentation; }
    @Override public AppReference windowApplication() { return application; }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (session != null) state.putString(SESSION, session.id());
        state.putLong(WINDOW, window);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (output != null) output.focus(focused);
        if (focused && session != null) {
            session.presentation.host(this);
            changed();
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (session != null) session.presentation.host(this);
    }
    @Override public void requestClose(boolean force) {
        if (pendingLaunch && session != null) { session.close(); finishAndRemoveTask(); return; }
        if (session != null && session.ready() && session.containsWindow(window)) session.closeWindow(window, force);
        else finishAndRemoveTask();
    }
    @Override public BuiltInWindowRegistry.ForceCloseAction forceCloseAction() {
        return new BuiltInWindowRegistry.ForceCloseAction(R.string.action_force_stop, getString(R.string.graphics_force_stop_client));
    }
    @Override public void onDestroy() {
        if (isFinishing() && pendingLaunch && session != null) session.close();
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
