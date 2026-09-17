package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.mekhontsev.magicdesk.x11.X11Session;
import java.util.List;

/** Session controls only: selecting or closing this manager never acquires an output. */
public final class X11ManagerActivity extends Activity implements X11Sessions.Listener {
    private X11Sessions.Session session;
    private DesktopUiFactory ui;
    private Button sessions;
    private TextView status;
    private ImageButton open, windows, execute, scale, stop;

    static Intent createIntent(Context context) { return new Intent(context, X11ManagerActivity.class); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.x11_title, R.drawable.ic_show_desktop);
        ui = new DesktopUiFactory(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        SystemBarInsets.addToPadding(root, true);
        root.addView(createSessionControls());
        status = new TextView(this);
        status.setTextColor(DesktopUiFactory.COLOR_MUTED);
        status.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        root.addView(status);
        setContentView(root);
        select(X11Sessions.find(state == null ? null : state.getString(X11Activity.SESSION)));
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
        scale = addAction(toolbar, R.drawable.ic_quick_controls, R.string.app_presentation_scale,
                () -> { if (session != null) X11ScaleDialog.show(this, session); });
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
        session = next;
        if (session != null) session.listen(this);
        onChanged();
    }

    @Override public void onChanged() {
        if (isDestroyed()) return;
        boolean ready = session != null && session.state() == X11Sessions.State.READY;
        sessions.setText(session == null ? getString(R.string.x11_sessions) : session.name + " " + session.display());
        open.setEnabled(ready);
        windows.setEnabled(ready);
        execute.setEnabled(ready);
        scale.setEnabled(ready);
        stop.setEnabled(session != null && !session.stopped());
        status.setText(session == null ? getString(R.string.x11_no_session)
                : !session.error().isEmpty() ? session.error() : session.state().name());
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && status != null) onChanged();
    }

    private void chooseSession() {
        List<X11Sessions.Session> items = X11Sessions.list();
        String[] labels = items.stream().map(item -> item.name + " " + item.display()).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.x11_sessions).setItems(labels, (dialog, which) -> {
            select(items.get(which));
        }).setPositiveButton(R.string.x11_new_session, (dialog, which) -> newSession())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseWindow() {
        if (session == null) return;
        List<X11Session.Window> windows = session.windows();
        String[] labels = windows.stream().map(item -> item.title().isBlank()
                ? "X11 " + Long.toUnsignedString(item.id()) : item.title()).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.x11_windows).setItems(labels, (dialog, which) -> {
            openWindow(windows.get(which).id());
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openWindow(long id) { X11Activity.openWindow(this, session, id); }

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
        if (session != null) state.putString(X11Activity.SESSION, session.id());
    }

    @Override public void onDestroy() {
        if (session != null) session.unlisten(this);
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
