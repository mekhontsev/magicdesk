package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.mekhontsev.magicdesk.x11.X11Session;

/** An ordinary Android window onto a retained X server or one selected X client window. */
public final class X11Activity extends Activity implements
        BuiltInWindowRegistry.PresentationSource, BuiltInWindowRegistry.ImmersiveSource,
        BuiltInWindowRegistry.CloseHandler, BuiltInWindowRegistry.ApplicationSource,
        BuiltInWindowRegistry.DesktopPresentationListener {
    static final String SESSION = "x11_session";
    static final String WINDOW = "x11_window";
    static final String DESKTOP_FILE = "x11_desktop_file";
    static final String APPLICATION = "x11_application";
    static final String RECENT_SCOPE = "x11_recent_scope";
    static final String BACKEND = "x11_backend";
    static final String KEYBOARD_DIRECTORY = "x11_keyboard_directory";
    private static final String COMMAND = "x11_command";
    private static final String NAME = "x11_name";
    private static final String DIRECTORY = "x11_directory";
    private X11Sessions.Session session;
    private volatile X11HostBinding binding;
    private HostedSurfaceView surface;
    private TextView status;
    private long window;
    private boolean seenWindow;
    private boolean application;
    private boolean provisional = true;
    private volatile BuiltInWindowRegistry.Presentation presentation;
    private RecentApplicationStore.Entry identityRecipe;
    private volatile AppReference windowApplication;

    static Intent createIntent(Context context) { return new Intent(context, X11Activity.class); }

    static Intent windowIntent(Context context, X11Sessions.Session session, long window) {
        return BuiltInWindowIdentity.bind(createIntent(context).putExtra(SESSION, session.id()).putExtra(WINDOW, window)
                .putExtra(APPLICATION, session.application),
                GraphicalApplicationLaunch.reference(context, session.windowRecipe(window)));
    }

    static Intent createApplicationIntent(Context context, String name, String command, String directory) {
        return createIntent(context).putExtra(NAME, name).putExtra(COMMAND, command).putExtra(DIRECTORY, directory)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.x11_title, R.drawable.ic_show_desktop);
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
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        window = state == null ? getIntent().getLongExtra(WINDOW, 0) : state.getLong(WINDOW);
        provisional = state == null ? window == 0 : state.getBoolean("x11_provisional", window == 0);
        String id = state == null ? getIntent().getStringExtra(SESSION) : state.getString(SESSION);
        final var recipe = getIntent().hasExtra(GraphicalApplicationLaunch.RECIPE) ? DesktopEntryFile.parseRecent(getIntent().getStringExtra(GraphicalApplicationLaunch.RECIPE)) : null;
        identityRecipe = recipe;
        windowApplication = GraphicalApplicationLaunch.reference(this, recipe);
        application = state == null ? getIntent().getBooleanExtra(APPLICATION,
                getIntent().hasExtra(COMMAND) && (recipe == null || recipe.shortcut().graphics == null
                        || !recipe.shortcut().graphics.desktop())) : state.getBoolean(APPLICATION);
        if (getIntent().hasExtra(COMMAND) && state == null && id == null) {
            try {
                DesktopExecBackend backend = DesktopExecBackend.parse(getIntent().getStringExtra(BACKEND));
                if (DesktopExecRunner.prepareBackend(this, backend) != DesktopExecRunner.StartResult.STARTED) {
                    status.setText(backend == DesktopExecBackend.TERMUX ? R.string.x11_permission_required
                            : R.string.capability_access_required);
                    return;
                }
                if (getIntent().hasExtra(GraphicalApplicationLaunch.RECIPE) && recipe == null) throw new IllegalArgumentException("Invalid X11 launch recipe");
                if (recipe != null) RecentApplications.requireEnvironment(this, recipe);
                select(X11Sessions.startCommand(this, getIntent().getStringExtra(NAME),
                        getIntent().getStringExtra(COMMAND), getIntent().getStringExtra(DIRECTORY),
                        getIntent().getStringExtra(DESKTOP_FILE), application, recipe, backend,
                        getIntent().getStringExtra(KEYBOARD_DIRECTORY)));
                if (getIntent().hasExtra(RECENT_SCOPE))
                    session.recordUse(RecentLaunchScope.valueOf(getIntent().getStringExtra(RECENT_SCOPE)));
            } catch (RuntimeException error) { showError(error); }
            return;
        }
        select(X11Sessions.find(id));
    }

    private void select(X11Sessions.Session next) {
        if (binding != null) binding.close(false);
        binding = null;
        session = next;
        seenWindow = false;
        if (session != null) {
            session.presentation.host(this);
            binding = new X11HostBinding(this, surface, session, window, application, this::onChanged);
        }
        onChanged();
    }

    private void onChanged() {
        if (isDestroyed() || isFinishing()) return;
        if (session != null && session.redirect() != null) {
            var redirect = session.redirect();
            window = redirect.window();
            provisional = false;
            select(redirect.session());
            return;
        }
        if (session != null && session.presentation.isClosed()) { finish(); return; }
        boolean ready = session != null && session.state() == X11Sessions.State.READY;
        if (application && ready) {
            long selected = X11WindowSelection.select(window, provisional, session.windows());
            if (selected < 0) { finish(); return; }
            if (selected != window) {
                window = selected;
                seenWindow = false;
            }
            for (var item : session.windows()) if (item.id() == window) provisional = item.provisional();
        }
        if (ready && window != 0) {
            session.claimWindow(window);
        }
        var currentRecipe = session == null ? null : session.windowRecipe(window);
        if (currentRecipe != identityRecipe) {
            identityRecipe = currentRecipe;
            windowApplication = GraphicalApplicationLaunch.reference(this, currentRecipe);
            DesktopRuntimeBridge.refreshTaskPresentations();
        }
        if (application && session != null && session.state() == X11Sessions.State.CLOSED) { finish(); return; }
        if (window == 0 && session != null) {
            present(session.name, null);
        }
        if (window != 0) {
            if (session == null || session.state() == X11Sessions.State.CLOSED
                    || session.state() == X11Sessions.State.FAILED) { finish(); return; }
            X11Session.Window info = session.windows().stream().filter(item -> item.id() == window).findFirst().orElse(null);
            if (info != null) {
                seenWindow = true;
                String title = info.title().isBlank() ? session.name : info.title();
                present(title, info.icon());
            } else if (seenWindow || ready) { finish(); return; }
        }
        status.setText(session == null ? getString(R.string.x11_no_session)
                : !session.error().isEmpty() ? session.error()
                : ready && application && window == 0 ? getString(R.string.x11_waiting_application) : session.state().name());
        status.setVisibility(ready && session.error().isEmpty() && (!application || window != 0) ? View.GONE : View.VISIBLE);
        if (binding != null) {
            try {
                binding.refresh(window, ready && (!application || window != 0));
            } catch (RuntimeException error) { status.setText(ShellAccess.usefulMessage(error)); status.setVisibility(View.VISIBLE); }
        }
    }

    @Override public BuiltInWindowRegistry.ImmersiveRequest immersiveRequest() {
        X11HostBinding current = binding;
        return current == null ? null : current.immersiveRequest();
    }

    @Override public void onImmersiveRejected() {
        if (binding != null) binding.rejectImmersive();
    }

    @Override public BuiltInWindowRegistry.Presentation taskPresentation() { return presentation; }
    @Override public AppReference windowApplication() { return windowApplication; }
    @Override public void desktopPresentationChanged() { if (binding != null) binding.presentationChanged(); }

    private void present(String title, Bitmap icon) {
        if (presentation != null && presentation.title().equals(title) && presentation.icon() == icon) return;
        presentation = new BuiltInWindowRegistry.Presentation(title, icon);
        setTitle(title);
        if (icon == null) DesktopTaskDescription.apply(this, title, R.drawable.ic_show_desktop);
        else DesktopTaskDescription.apply(this, title, icon);
        DesktopRuntimeBridge.refreshTaskPresentations();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (binding != null) binding.focusChanged(focused);
        if (focused && session != null) session.presentation.host(this);
        if (focused && surface != null) onChanged();
        else if (binding != null) binding.updateExchangeFocus();
        if (binding != null) binding.presentationChanged();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (binding != null) { binding.updateDensity(); binding.presentationChanged(); }
        if (session != null) session.presentation.host(this);
    }

    @Override public void onMultiWindowModeChanged(boolean multiWindow, android.content.res.Configuration configuration) {
        super.onMultiWindowModeChanged(multiWindow, configuration);
        if (binding != null) binding.presentationChanged();
    }

    static void openWindow(Activity source, X11Sessions.Session selected, long id) {
        selected.claimWindow(id);
        ToolApplications.openSibling(source, windowIntent(source, selected, id),
                error -> {
                    if (error != null) {
                        new AlertDialog.Builder(source).setMessage(ShellAccess.usefulMessage(error))
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
    }

    private void showError(Throwable error) {
        new AlertDialog.Builder(this).setMessage(ShellAccess.usefulMessage(error))
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @Override public void requestClose(boolean force) {
        if (binding != null && binding.requestClose(force)) return;
        finishAndRemoveTask();
    }

    @Override public BuiltInWindowRegistry.ForceCloseAction forceCloseAction() {
        return new BuiltInWindowRegistry.ForceCloseAction(
                window == 0 ? R.string.x11_stop_session_action : R.string.action_force_stop,
                getString(window == 0 ? R.string.x11_force_stop_session : R.string.x11_force_stop_client));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (session != null) state.putString(SESSION, session.id());
        state.putLong(WINDOW, window);
        state.putBoolean(APPLICATION, application);
        state.putBoolean("x11_provisional", provisional);
    }

    @Override public void onDestroy() {
        if (binding != null) binding.close(isFinishing());
        binding = null;
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
