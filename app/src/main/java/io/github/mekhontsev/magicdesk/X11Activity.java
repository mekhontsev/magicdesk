package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.X11Session;
import java.util.List;

/** An ordinary Android window onto a retained X server or one selected X client window. */
public final class X11Activity extends Activity implements X11Sessions.Listener {
    static final String SESSION = "x11_session";
    static final String WINDOW = "x11_window";
    private X11Sessions.Session session;
    private X11Session.Output output;
    private X11SurfaceView surface;
    private DesktopUiFactory ui;
    private Button sessions;
    private TextView status;
    private ImageButton execute, stop;
    private long window;

    static Intent createIntent(Context context) { return new Intent(context, X11Activity.class); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.x11_title, R.drawable.ic_show_desktop);
        ui = new DesktopUiFactory(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        SystemBarInsets.addToPadding(root, true);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        sessions = ui.menuItem(R.string.x11_sessions, DesktopUiFactory.COLOR_TEXT);
        sessions.setSingleLine(true);
        sessions.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sessions.setOnClickListener(v -> chooseSession());
        toolbar.addView(sessions, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        addAction(toolbar, R.drawable.ic_add, R.string.x11_new_session, this::newSession);
        execute = addAction(toolbar, R.drawable.ic_play, R.string.x11_run_command, this::command);
        addAction(toolbar, R.drawable.ic_keyboard, R.string.touchpad_gesture_keyboard, () -> {
            surface.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(surface, InputMethodManager.SHOW_IMPLICIT);
        });
        stop = addAction(toolbar, R.drawable.ic_close, R.string.x11_stop_session, () -> {
            X11Sessions.Session selected = session;
            if (selected != null) new AlertDialog.Builder(this).setTitle(R.string.x11_stop_session)
                    .setMessage(selected.name).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> selected.close()).show();
        });
        root.addView(toolbar);
        status = new TextView(this);
        status.setTextColor(DesktopUiFactory.COLOR_MUTED);
        status.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        root.addView(status);
        surface = new X11SurfaceView(this);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        window = state == null ? getIntent().getLongExtra(WINDOW, 0) : state.getLong(WINDOW);
        String id = state == null ? getIntent().getStringExtra(SESSION) : state.getString(SESSION);
        select(X11Sessions.find(id));
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
        if (session != null) session.listen(this);
        onChanged();
    }

    @Override public void onChanged() {
        if (isDestroyed()) return;
        boolean ready = session != null && session.state() == X11Sessions.State.READY;
        sessions.setText(session == null ? getString(R.string.x11_sessions) : session.name + " " + session.display());
        execute.setEnabled(ready);
        stop.setEnabled(session != null && !session.stopped());
        status.setText(session == null ? getString(R.string.x11_no_session)
                : session.error().isEmpty() ? session.state().name() : session.error());
        status.setVisibility(ready && session.error().isEmpty() ? View.GONE : View.VISIBLE);
        if (ready && output == null) {
            try {
                output = session.openOutput(window);
                surface.bind(output);
                surface.requestFocus();
            } catch (RuntimeException error) { status.setText(ShellAccess.usefulMessage(error)); status.setVisibility(View.VISIBLE); }
        } else if (!ready && output != null) { surface.release(); output = null; }
    }

    @Override public void onFrame(X11Session.Output source, int width, int height, boolean available) {
        if (source == output) surface.frame(available ? width : 0, available ? height : 0);
    }

    private void chooseSession() {
        List<X11Sessions.Session> items = X11Sessions.list();
        String[] labels = items.stream().map(item -> item.name + " " + item.display()).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.x11_sessions).setItems(labels, (dialog, which) -> {
            window = 0;
            select(items.get(which));
        }).setPositiveButton(R.string.x11_new_session, (dialog, which) -> newSession())
                .setNegativeButton(android.R.string.cancel, null).show();
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
                        select(X11Sessions.start(this, name.getText().toString().isBlank()
                                ? "X11" : name.getText().toString(), command.getText().toString()));
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
    }

    @Override public void onDestroy() {
        if (session != null) session.unlisten(this);
        if (surface != null) surface.release();
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
