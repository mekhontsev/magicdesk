package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.X11Session;
import java.util.List;

/** An ordinary Android window onto a retained X server or one selected X client window. */
public final class X11Activity extends Activity implements X11Sessions.Listener, BuiltInWindowRegistry.PresentationSource {
    static final String SESSION = "x11_session";
    static final String WINDOW = "x11_window";
    private static final String APPLICATION = "x11_application";
    private static final String COMMAND = "x11_command";
    private static final String NAME = "x11_name";
    private static final String DIRECTORY = "x11_directory";
    private X11Sessions.Session session;
    private X11Session.Output output;
    private X11SurfaceView surface;
    private DesktopUiFactory ui;
    private Button sessions;
    private TextView status;
    private ImageButton open, windows, execute, stop;
    private long window;
    private boolean seenWindow;
    private boolean application;
    private boolean manager;
    private volatile BuiltInWindowRegistry.Presentation presentation;
    private AutoCloseable clipboardObserver;
    private X11Sessions.Session clipboardSession;

    static Intent createIntent(Context context) { return new Intent(context, X11Activity.class); }

    static Intent createApplicationIntent(Context context, String name, String command, String directory) {
        return createIntent(context).putExtra(NAME, name).putExtra(COMMAND, command).putExtra(DIRECTORY, directory)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = !getIntent().hasExtra(SESSION) && !getIntent().hasExtra(WINDOW)
                && !getIntent().hasExtra(COMMAND);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.x11_title, R.drawable.ic_show_desktop);
        ui = new DesktopUiFactory(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        SystemBarInsets.addToPadding(root, true);
        if (manager) root.addView(createSessionControls());
        status = new TextView(this);
        status.setTextColor(DesktopUiFactory.COLOR_MUTED);
        status.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        root.addView(status);
        surface = new X11SurfaceView(this);
        surface.setVisibility(manager ? View.GONE : View.VISIBLE);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        window = state == null ? getIntent().getLongExtra(WINDOW, 0) : state.getLong(WINDOW);
        String id = state == null ? getIntent().getStringExtra(SESSION) : state.getString(SESSION);
        application = state == null ? getIntent().hasExtra(COMMAND) : state.getBoolean(APPLICATION);
        if (application && state == null && id == null) {
            try {
                if (!TermuxIntegration.ensureRunCommandPermission(this)) {
                    status.setText(R.string.x11_permission_required);
                    return;
                }
                select(X11Sessions.startApplication(this, getIntent().getStringExtra(NAME),
                        getIntent().getStringExtra(COMMAND), getIntent().getStringExtra(DIRECTORY)));
            } catch (RuntimeException error) { showError(error); }
            return;
        }
        select(X11Sessions.find(id));
    }

    private LinearLayout createSessionControls() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        sessions = ui.menuItem(R.string.x11_sessions, DesktopUiFactory.COLOR_TEXT);
        sessions.setSingleLine(true);
        sessions.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sessions.setOnClickListener(v -> chooseSession());
        toolbar.addView(sessions, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        addAction(toolbar, R.drawable.ic_add, R.string.x11_new_session, this::newSession);
        open = addAction(toolbar, R.drawable.ic_show_desktop, R.string.x11_open_session, () -> openWindow(0));
        windows = addAction(toolbar, R.drawable.ic_file_new_window, R.string.x11_windows, this::chooseWindow);
        execute = addAction(toolbar, R.drawable.ic_play, R.string.x11_run_command, this::command);
        stop = addAction(toolbar, R.drawable.ic_close, R.string.x11_stop_session, () -> {
            X11Sessions.Session selected = session;
            if (selected != null) new AlertDialog.Builder(this).setTitle(R.string.x11_stop_session)
                    .setMessage(selected.name).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> selected.close()).show();
        });
        return toolbar;
    }

    private ImageButton addAction(LinearLayout toolbar, int icon, int title, Runnable action) {
        ImageButton button = ui.menuIconButton(icon, title);
        button.setOnClickListener(v -> action.run());
        toolbar.addView(button, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        return button;
    }

    private void select(X11Sessions.Session next) {
        if (session != null) session.unlisten(this);
        surface.release();
        output = null;
        session = next;
        seenWindow = false;
        if (session != null) session.listen(this);
        onChanged();
    }

    @Override public void onChanged() {
        if (isDestroyed()) return;
        boolean ready = session != null && session.state() == X11Sessions.State.READY;
        if (application && ready && window == 0) {
            window = session.windows().stream().filter(X11Session.Window::mapped)
                    .mapToLong(X11Session.Window::id).findFirst().orElse(0);
        }
        if (ready && window != 0) {
            session.claimWindow(window);
        }
        if (!manager && ready && session.application && hasWindowFocus()) for (X11Session.Window item : session.windows()) {
            if (item.mapped() && session.claimWindow(item.id())) openWindow(item.id());
        }
        if (application && session != null && session.state() == X11Sessions.State.CLOSED) { finish(); return; }
        if (!manager && window == 0 && session != null) {
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
        if (manager) {
            sessions.setText(session == null ? getString(R.string.x11_sessions) : session.name + " " + session.display());
            open.setEnabled(ready);
            windows.setEnabled(ready);
            execute.setEnabled(ready);
            stop.setEnabled(session != null && !session.stopped());
        }
        status.setText(session == null ? getString(R.string.x11_no_session)
                : !session.error().isEmpty() ? session.error()
                : ready && application && window == 0 ? getString(R.string.x11_waiting_application) : session.state().name());
        status.setVisibility(!manager && ready && session.error().isEmpty() && (!application || window != 0) ? View.GONE : View.VISIBLE);
        if (!manager && ready && output == null && (!application || window != 0)) {
            try {
                output = session.openOutput(window);
                surface.bind(output);
                surface.requestFocus();
            } catch (RuntimeException error) { status.setText(ShellAccess.usefulMessage(error)); status.setVisibility(View.VISIBLE); }
        } else if (!ready && output != null) { surface.release(); output = null; }
        updateClipboard();
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
        if (focused && surface != null) onChanged();
        else updateClipboard();
    }

    private void updateClipboard() {
        X11Sessions.Session next = !manager && hasWindowFocus() && session != null && session.state() == X11Sessions.State.READY
                ? session : null;
        if (clipboardSession == next) return;
        releaseClipboard();
        clipboardSession = next;
        if (next != null) {
            next.claimClipboard(this);
            clipboardObserver = AndroidClipboardGateway.get(this).observe(this::publishClipboard);
            publishClipboard();
        }
    }

    private void publishClipboard() {
        if (clipboardSession == null || !hasWindowFocus()) return;
        var value = AndroidClipboardGateway.get(this).readText();
        if (value.metadata.access == AndroidClipboardGateway.Access.AVAILABLE
                || value.metadata.access == AndroidClipboardGateway.Access.EMPTY) {
            try { clipboardSession.offerClipboard(this, value.text); }
            catch (IllegalArgumentException tooLarge) { android.util.Log.w("MagicDesk", tooLarge.getMessage()); }
        }
    }

    @Override public void onClipboard(String text) {
        if (clipboardSession == session && hasWindowFocus()) AndroidClipboardGateway.get(this).writeText("X11", text, false);
    }

    private void releaseClipboard() {
        if (clipboardObserver != null) {
            try { clipboardObserver.close(); } catch (Exception error) { android.util.Log.w("MagicDesk", "X11 clipboard observer", error); }
            clipboardObserver = null;
        }
        if (clipboardSession != null) clipboardSession.releaseClipboard(this);
        clipboardSession = null;
    }

    @Override public void onFrame(X11Session.Output source, int width, int height, boolean available) {
        if (source == output) surface.frame(available ? width : 0, available ? height : 0);
    }

    private void chooseSession() {
        List<X11Sessions.Session> items = X11Sessions.list();
        String[] labels = items.stream().map(item -> item.name + " " + item.display()).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.x11_sessions).setItems(labels, (dialog, which) -> {
            window = 0;
            application = false;
            select(items.get(which));
            openWindow(0);
        }).setPositiveButton(R.string.x11_new_session, (dialog, which) -> newSession())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseWindow() {
        if (session == null) return;
        X11Sessions.Session selected = session;
        List<X11Session.Window> windows = selected.windows();
        String[] labels = windows.stream().map(item -> item.title().isBlank()
                ? "X11 " + Long.toUnsignedString(item.id()) : item.title()).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.x11_windows).setItems(labels, (dialog, which) -> {
            openWindow(windows.get(which).id());
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openWindow(long id) {
        X11Sessions.Session selected = session;
        ToolApplications.openSibling(this, createIntent(this).putExtra(SESSION, selected.id()).putExtra(WINDOW, id),
                error -> {
                    if (error != null) { selected.releaseWindowClaim(id); showError(error); }
                });
    }

    private EditText field(LinearLayout parent, int hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private void newSession() {
        if (!TermuxIntegration.ensureRunCommandPermission(this)) return;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        EditText name = field(fields, R.string.x11_session_name);
        EditText command = field(fields, R.string.x11_command_optional);
        new AlertDialog.Builder(this).setTitle(R.string.x11_new_session).setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.x11_start, (dialog, which) -> {
                    try {
                        window = 0;
                        application = false;
                        select(X11Sessions.start(this, name.getText().toString().isBlank()
                                ? "X11" : name.getText().toString(), command.getText().toString()));
                        openWindow(0);
                    } catch (RuntimeException error) { showError(error); }
                }).show();
    }

    private void command() {
        if (session == null) return;
        EditText command = new EditText(this);
        command.setSingleLine(true);
        command.setHint(R.string.x11_run_command);
        X11Sessions.Session selected = session;
        new AlertDialog.Builder(this).setTitle(R.string.x11_run_command).setView(command)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.x11_start, (dialog, which) -> {
                    try { selected.execute(command.getText().toString()); }
                    catch (RuntimeException error) { showError(error); }
                }).show();
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
    }

    @Override public void onDestroy() {
        releaseClipboard();
        if (isFinishing() && application && window == 0 && session != null) session.close();
        if (isFinishing() && window != 0 && session != null) session.closeWindow(window);
        if (session != null) session.unlisten(this);
        if (surface != null) surface.release();
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
