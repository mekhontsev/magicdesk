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
import java.util.List;

/** Session controls only: selecting or closing this manager never acquires an output. */
public final class GraphicalSessionsActivity extends Activity {
    private GraphicalSessions.Session session;
    private final Runnable listener = this::onChanged;
    private DesktopUiFactory ui;
    private Button sessions;
    private Button shellWorkspace;
    private TextView status;
    private ImageButton open, windows, execute, scale, stop;

    static Intent createIntent(Context context) { return new Intent(context, GraphicalSessionsActivity.class); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        BuiltInWindowRegistry.register(this);
        DesktopTaskDescription.apply(this, R.string.graphics_title, R.drawable.ic_show_desktop);
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
        shellWorkspace = ui.menuItem(R.string.graphics_shell_workspace, DesktopUiFactory.COLOR_TEXT);
        shellWorkspace.setOnClickListener(view -> chooseShellWorkspace());
        root.addView(shellWorkspace);
        GraphicalShells.listen(listener);
        setContentView(root);
        select(GraphicalSessions.find(state == null ? null : state.getString("session")));
    }

    private LinearLayout createSessionControls() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        sessions = ui.menuItem(R.string.graphics_sessions, DesktopUiFactory.COLOR_TEXT);
        sessions.setSingleLine(true);
        sessions.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sessions.setOnClickListener(v -> chooseSession());
        toolbar.addView(sessions, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        addAction(toolbar, R.drawable.ic_add, R.string.graphics_new_session, this::newSession);
        open = addAction(toolbar, R.drawable.ic_show_desktop, R.string.x11_open_session, () -> openWindow(0));
        windows = addAction(toolbar, R.drawable.ic_file_new_window, R.string.graphics_windows, this::chooseWindow);
        execute = addAction(toolbar, R.drawable.ic_play, R.string.x11_run_command, this::command);
        scale = addAction(toolbar, R.drawable.ic_quick_controls, R.string.app_presentation_scale,
                () -> { if (session != null) GraphicalScaleDialog.show(this, session); });
        stop = addAction(toolbar, R.drawable.ic_close, R.string.graphics_stop_session, () -> {
            GraphicalSessions.Session selected = session;
            if (selected != null) new AlertDialog.Builder(this).setTitle(R.string.graphics_stop_session)
                    .setMessage(selected.name()).setNegativeButton(android.R.string.cancel, null)
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

    private void select(GraphicalSessions.Session next) {
        if (session != null) { session.unlisten(listener); session.unwatch(this); }
        session = next;
        if (session != null) session.listen(listener);
        onChanged();
    }

    private void onChanged() {
        if (isDestroyed()) return;
        boolean ready = session != null && session.ready();
        sessions.setText(session == null ? getString(R.string.graphics_sessions) : session.name() + " / " + session.protocol());
        open.setEnabled(ready && session.desktop());
        windows.setEnabled(ready);
        execute.setEnabled(ready && session.canExecute());
        scale.setEnabled(ready);
        stop.setEnabled(session != null && !session.stopped());
        shellWorkspace.setEnabled(ready && session.canIntegrateShell());
        var shell = session == null ? null : GraphicalShells.state(session.id());
        shellWorkspace.setText(getString(R.string.graphics_shell_workspace) + ": "
                + (shell == null || shell.displayId() < 0 ? getString(R.string.graphics_shell_separate)
                : getString(R.string.graphics_shell_display, shell.displayId())));
        status.setText(session == null ? getString(R.string.graphics_no_session)
                : !session.error().isEmpty() ? session.error()
                : shell != null && !shell.error().isEmpty() ? shell.error() : session.state());
    }

    private void chooseShellWorkspace() {
        var selected = session;
        if (selected == null) return;
        var items = DesktopRuntimeBridge.getWorkspaces().stream().filter(DesktopSessionSnapshot::hasHost).toList();
        var labels = new String[items.size() + 1];
        labels[0] = getString(R.string.graphics_shell_separate);
        int current = 0;
        String id = GraphicalShells.state(selected.id()).workspaceId();
        for (int i = 0; i < items.size(); i++) {
            labels[i + 1] = getString(R.string.graphics_shell_display, items.get(i).activeWorkspaceDisplayId());
            if (items.get(i).workspace().id.equals(id)) current = i + 1;
        }
        new AlertDialog.Builder(this).setTitle(R.string.graphics_shell_workspace)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    try { GraphicalShells.select(selected, which == 0 ? "" : items.get(which - 1).workspace().id); }
                    catch (RuntimeException error) { showError(error); }
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && status != null) onChanged();
    }

    private void chooseSession() {
        List<GraphicalSessions.Session> items = GraphicalSessions.list();
        String[] labels = items.stream().map(item -> item.name() + " / " + item.protocol()).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.graphics_sessions).setItems(labels, (dialog, which) -> {
            select(items.get(which));
        }).setPositiveButton(R.string.graphics_new_session, (dialog, which) -> newSession())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseWindow() {
        if (session == null) return;
        List<GraphicalSessions.Window> windows = session.windows();
        String[] labels = windows.stream().map(item -> item.title().isBlank()
                ? session.protocol() + " " + Long.toUnsignedString(item.id()) : item.title()).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.graphics_windows).setItems(labels, (dialog, which) -> {
            openWindow(windows.get(which).id());
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openWindow(long id) {
        if (session == null) return;
        try { ToolApplications.openSibling(this, session.windowIntent(this, id), error -> { if (error != null) showError(error); }); }
        catch (RuntimeException error) { showError(error); }
    }

    private EditText field(LinearLayout parent, int hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private void newSession() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        EditText name = field(fields, R.string.x11_session_name);
        EditText command = field(fields, R.string.x11_command_optional);
        android.widget.Spinner protocol = new android.widget.Spinner(this);
        var protocols = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"X11", "Wayland"});
        protocols.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocol.setAdapter(protocols);
        fields.addView(protocol);
        android.widget.CheckBox desktop = new android.widget.CheckBox(this);
        desktop.setText(R.string.graphics_nested_desktop);
        fields.addView(desktop);
        protocol.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                desktop.setVisibility(position == 1 ? android.view.View.VISIBLE : android.view.View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        android.widget.Spinner backend = new android.widget.Spinner(this);
        var options = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Termux", "Shell / root"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backend.setAdapter(options);
        backend.setSelection(TermuxIntegration.isAvailable(this) ? 0 : 1);
        fields.addView(backend);
        EditText keyboard = field(fields, R.string.x11_keyboard_directory);
        keyboard.setVisibility(backend.getSelectedItemPosition() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        backend.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                keyboard.setVisibility(position == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.graphics_new_session).setView(fields)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.x11_start, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                    try {
                        var executor = backend.getSelectedItemPosition() == 0 ? DesktopExecBackend.TERMUX : DesktopExecBackend.SHELL;
                        if (DesktopExecRunner.prepareBackend(this, executor) != DesktopExecRunner.StartResult.STARTED) return;
                        var selectedProtocol = protocol.getSelectedItemPosition() == 0
                                ? GraphicalProtocol.X11 : GraphicalProtocol.WAYLAND;
                        select(GraphicalSessions.start(this, selectedProtocol, name.getText().toString().isBlank()
                                ? protocol.getSelectedItem().toString() : name.getText().toString(),
                                command.getText().toString(), "", executor, keyboard.getText().toString(), desktop.isChecked()));
                        if (session.desktop()) openWindow(0);
                        else session.watch(this);
                        dialog.dismiss();
                    } catch (RuntimeException error) { showError(error); }
                }));
        dialog.show();
    }

    private void command() {
        if (session == null) return;
        EditText command = new EditText(this);
        command.setSingleLine(true);
        command.setHint(R.string.x11_run_command);
        GraphicalSessions.Session selected = session;
        new AlertDialog.Builder(this).setTitle(R.string.x11_run_command).setView(command)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.x11_start, (dialog, which) -> {
                    try { selected.execute(command.getText().toString(), ""); }
                    catch (RuntimeException error) { showError(error); }
                }).show();
    }

    private void showError(Throwable error) {
        new AlertDialog.Builder(this).setMessage(ShellAccess.usefulMessage(error))
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (session != null) state.putString("session", session.id());
    }

    @Override public void onDestroy() {
        GraphicalShells.unlisten(listener);
        if (session != null) { session.unlisten(listener); session.unwatch(this); }
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }
}
