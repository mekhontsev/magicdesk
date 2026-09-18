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
        BuiltInWindowRegistry.PresentationSource, BuiltInWindowRegistry.ImmersiveSource {
    static final String SESSION = "x11_session";
    static final String WINDOW = "x11_window";
    static final String DESKTOP_FILE = "x11_desktop_file";
    static final String APPLICATION = "x11_application";
    static final String RECIPE = "x11_recipe";
    static final String RECENT_SCOPE = "x11_recent_scope";
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

    static Intent createIntent(Context context) { return new Intent(context, X11Activity.class); }

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
        SystemBarInsets.addToPadding(root, true);
        status = new TextView(this);
        status.setTextColor(DesktopUiFactory.COLOR_MUTED);
        status.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        root.addView(status);
        surface = new HostedSurfaceView(this);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        window = state == null ? getIntent().getLongExtra(WINDOW, 0) : state.getLong(WINDOW);
        if (state != null) provisional = state.getBoolean("x11_provisional", true);
        String id = state == null ? getIntent().getStringExtra(SESSION) : state.getString(SESSION);
        final var recipe = getIntent().hasExtra(RECIPE) ? DesktopEntryFile.parseRecent(getIntent().getStringExtra(RECIPE)) : null;
        application = state == null ? getIntent().getBooleanExtra(APPLICATION,
                getIntent().hasExtra(COMMAND) && (recipe == null || !recipe.shortcut().x11Desktop)) : state.getBoolean(APPLICATION);
        if (getIntent().hasExtra(COMMAND) && state == null && id == null) {
            try {
                if (!TermuxIntegration.ensureRunCommandPermission(this)) {
                    status.setText(R.string.x11_permission_required);
                    return;
                }
                if (getIntent().hasExtra(RECIPE) && recipe == null) throw new IllegalArgumentException("Invalid X11 launch recipe");
                if (recipe != null) RecentApplications.requireEnvironment(this, recipe);
                select(X11Sessions.startCommand(this, getIntent().getStringExtra(NAME),
                        getIntent().getStringExtra(COMMAND), getIntent().getStringExtra(DIRECTORY),
                        getIntent().getStringExtra(DESKTOP_FILE), application, recipe));
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
        if (session != null) binding = new X11HostBinding(this, surface, session, window, application, this::onChanged);
        onChanged();
    }

    private void onChanged() {
        if (isDestroyed()) return;
        boolean ready = session != null && session.state() == X11Sessions.State.READY;
        if (application && ready) {
            long selected = X11WindowSelection.select(window, provisional, session.windows());
            if (selected < 0) { finish(); return; }
            if (selected != window) {
                session.releaseWindowClaim(window);
                window = selected;
                seenWindow = false;
            }
            for (var item : session.windows()) if (item.id() == window) provisional = item.provisional();
        }
        if (ready && window != 0) {
            session.claimWindow(window);
        }
        if (ready && session.application && hasWindowFocus()) for (X11Session.Window item : session.windows()) {
            if (item.mapped() && !item.provisional() && session.claimWindow(item.id())) openWindow(this, session, item.id());
        }
        if (application && session != null && session.state() == X11Sessions.State.CLOSED) { finish(); return; }
        if (window == 0 && session != null) {
            present(session.name, null);
        }
        if (window != 0 && session != null) {
            X11Session.Window info = session.windows().stream().filter(item -> item.id() == window).findFirst().orElse(null);
            if (info != null) {
                seenWindow = true;
                String title = info.title().isBlank() ? session.name : info.title();
                present(title, info.icon());
            } else if (seenWindow) { finish(); return; }
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
        if (focused && surface != null) onChanged();
        else if (binding != null) binding.updateExchangeFocus();
        if (binding != null) binding.presentationChanged();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (binding != null) { binding.updateDensity(); binding.presentationChanged(); }
    }

    @Override public void onMultiWindowModeChanged(boolean multiWindow, android.content.res.Configuration configuration) {
        super.onMultiWindowModeChanged(multiWindow, configuration);
        if (binding != null) binding.presentationChanged();
    }

    static void openWindow(Activity source, X11Sessions.Session selected, long id) {
        ToolApplications.openSibling(source, createIntent(source).putExtra(SESSION, selected.id()).putExtra(WINDOW, id),
                error -> {
                    if (error != null) {
                        selected.releaseWindowClaim(id);
                        new AlertDialog.Builder(source).setMessage(ShellAccess.usefulMessage(error))
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
    }

    private void showError(Throwable error) {
        new AlertDialog.Builder(this).setMessage(ShellAccess.usefulMessage(error))
                .setPositiveButton(android.R.string.ok, null).show();
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
