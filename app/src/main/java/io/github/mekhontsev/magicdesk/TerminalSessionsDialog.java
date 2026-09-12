package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Shared picker for retained PTYs and Termux-owned tmux sessions, without Desktop ownership. */
final class TerminalSessionsDialog {
    private final Activity activity;
    private final ToolLaunchTarget target;
    private final String uniqueId;
    private final SessionsAdapter adapter = new SessionsAdapter();
    private final TextView status;
    private final AlertDialog dialog;
    private List<TerminalSessions.Item> items = List.of();
    private TmuxSessionProvider.Snapshot tmux;
    private boolean loading;

    private TerminalSessionsDialog(Activity activity, ToolLaunchTarget target, String uniqueId) {
        this.activity = activity;
        this.target = target;
        this.uniqueId = uniqueId;
        status = new TextView(activity);
        status.setTextSize(13);
        status.setPadding(dp(24), dp(8), dp(24), dp(8));
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.terminal_sessions)
                .setAdapter(adapter, (picker, index) -> open(items.get(index)))
                .setPositiveButton(R.string.terminal_new, null)
                .setNeutralButton(R.string.action_refresh, null)
                .setNegativeButton(android.R.string.cancel, null).create();
    }

    static void show(Activity activity) {
        show(activity, ToolLaunchTarget.resolve("auto", activity.getDisplay() == null
                ? 0 : activity.getDisplay().getDisplayId(), DesktopRuntimeBridge.workspaceDisplayIds()), null);
    }

    static void show(Activity activity, ToolLaunchTarget target, String uniqueId) {
        final var picker = new TerminalSessionsDialog(activity, target, uniqueId);
        picker.dialog.show();
        picker.dialog.getListView().addFooterView(picker.status, null, false);
        picker.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> picker.create());
        picker.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> picker.load());
        picker.dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            picker.actions(picker.items.get(position)); return true;
        });
        picker.load();
    }

    private void load() {
        if (loading || !alive()) return;
        loading = true;
        render();
        status.setText(R.string.console_tmux_loading);
        status.setVisibility(View.VISIBLE);
        TmuxSessionProvider.list(activity, (snapshot, error) -> activity.runOnUiThread(() -> {
            loading = false;
            if (!alive()) return;
            tmux = snapshot;
            render();
            final String message = error != null ? activity.getString(R.string.console_tmux_list_failed,
                    ShellAccess.usefulMessage(error)) : snapshot != null && !snapshot.available ? snapshot.detail
                    : items.isEmpty() ? activity.getString(R.string.terminal_no_sessions) : "";
            status.setText(message);
            status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        }));
    }

    private boolean alive() { return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }

    private void render() {
        items = TerminalSessions.merge(ConsoleTerminalRegistry.list(), tmux);
        adapter.notifyDataSetChanged();
    }

    private String title(TerminalSessions.Item item) {
        return item.tmux() != null ? item.tmux().name : item.terminal().taskLabel(activity.getString(
                "termux".equals(item.terminal().backend) ? R.string.console_termux_title : R.string.console_title));
    }

    private void open(TerminalSessions.Item item) {
        if (item.terminal() != null) openIntent(CommandConsoleActivity.attachIntent(activity, item.terminal()));
        else prepareTmux(item.tmux().id, null);
    }

    private void openIntent(Intent intent) {
        dialog.dismiss();
        TerminalSessions.open(activity, intent, target, uniqueId, this::result);
    }

    private void prepareTmux(String id, String name) {
        TmuxSessionProvider.prepare(activity, id, name, (session, error) -> {
            if (error != null) { result(error); return; }
            TmuxSessionProvider.list(activity, (snapshot, failure) -> activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure != null) { result(failure); return; }
                openIntent(TerminalSessions.tmuxIntent(activity, session, snapshot));
            }));
        });
    }

    private void create() {
        new AlertDialog.Builder(activity).setTitle(R.string.terminal_new)
                .setItems(new String[]{activity.getString(R.string.console_title),
                        activity.getString(R.string.console_termux_title), activity.getString(R.string.console_tmux_new_session)},
                        (which, choice) -> {
                            if (choice == 2) editName(activity.getString(R.string.console_tmux_new_session), "", name -> {
                                TmuxSessionProvider.normalizeName(name); prepareTmux(null, name);
                            });
                            else openIntent(choice == 0 ? CommandConsoleActivity.createIntent(activity)
                                    : CommandConsoleActivity.createTermuxIntent(activity));
                        }).show();
    }

    private void actions(TerminalSessions.Item item) {
        final boolean connection = item.terminal() != null;
        final String[] choices = connection
                ? new String[]{activity.getString(R.string.action_rename), activity.getString(R.string.terminal_detach),
                    activity.getString(R.string.terminal_end_session)}
                : new String[]{activity.getString(R.string.action_rename), activity.getString(R.string.terminal_end_session)};
        new AlertDialog.Builder(activity).setTitle(title(item)).setItems(choices, (which, index) -> {
            if (index == 0) {
                editName(activity.getString(R.string.action_rename), title(item), name -> {
                    if (item.tmux() != null) TmuxSessionProvider.rename(activity, item.tmux(), name, this::changed);
                    else { ConsoleTerminalRegistry.rename(item.terminal().id, name); load(); }
                });
            } else if (connection && index == 1) {
                ConsoleTerminalRegistry.hide(item.terminal().id); load();
            } else if (item.tmux() != null) {
                new AlertDialog.Builder(activity).setTitle(R.string.terminal_end_session)
                        .setMessage(activity.getString(R.string.terminal_end_tmux_confirm, title(item)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.terminal_end_session,
                                (confirm, button) -> TmuxSessionProvider.end(activity, item.tmux(), this::changed)).show();
            } else confirmEnd(activity, item.terminal().id, this::load);
        }).show();
    }

    private void editName(String title, String value, java.util.function.Consumer<String> save) {
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        input.setText(value);
        input.selectAll();
        final LinearLayout container = new LinearLayout(activity);
        container.setPadding(dp(24), dp(8), dp(24), 0);
        container.addView(input, new LinearLayout.LayoutParams(-1, -2));
        final AlertDialog editor = new AlertDialog.Builder(activity).setTitle(title).setView(container)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
        editor.setOnShowListener(ignored -> editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try { save.accept(input.getText().toString()); editor.dismiss(); }
            catch (IllegalArgumentException error) { input.setError(ShellAccess.usefulMessage(error)); }
        }));
        editor.show();
        input.requestFocus();
    }

    private static void confirmEnd(Activity activity, String id, Runnable complete) {
        final var session = ConsoleTerminalRegistry.status(id);
        if (session == null) return;
        final boolean tmux = !session.tmuxSessionId.isEmpty();
        new AlertDialog.Builder(activity).setTitle(tmux ? R.string.terminal_detach : R.string.terminal_end_session)
                .setMessage(tmux ? R.string.terminal_detach_tmux_confirm : R.string.terminal_end_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(tmux ? R.string.terminal_detach : R.string.terminal_end_session, (dialog, which) -> {
                    ConsoleTerminalRegistry.close(id); complete.run();
                }).show();
    }

    private void result(Throwable error) {
        activity.runOnUiThread(() -> {
            if (error != null && !activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void changed(Throwable error) { result(error); activity.runOnUiThread(this::load); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private final class SessionsAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            final var item = items.get(position);
            final LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(24), dp(8), dp(8), dp(8));
            final LinearLayout text = new LinearLayout(activity);
            text.setOrientation(LinearLayout.VERTICAL);
            final TextView name = new TextView(activity);
            name.setText(title(item)); name.setTextSize(16); name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(name);
            final TextView detail = new TextView(activity);
            detail.setTextSize(12); detail.setMaxLines(2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            final String state = activity.getString(item.terminal() != null && item.terminal().taskId >= 0
                    ? R.string.terminal_window_open : R.string.terminal_window_closed);
            detail.setText(item.tmux() == null ? item.terminal().backend + " | " + state + "\n" + item.terminal().workingDirectory
                    : "tmux | " + activity.getResources().getQuantityString(R.plurals.console_tmux_windows,
                            item.tmux().windows, item.tmux().windows) + "\n"
                            + activity.getResources().getQuantityString(R.plurals.terminal_tmux_clients,
                                    item.tmux().attachedClients, item.tmux().attachedClients) + " | " + state);
            text.addView(detail);
            row.addView(text, new LinearLayout.LayoutParams(0, dp(64), 1));
            final ImageButton more = new ImageButton(activity);
            more.setImageResource(R.drawable.ic_arrow_down);
            more.setContentDescription(activity.getString(R.string.terminal_session_actions, title(item)));
            more.setTooltipText(more.getContentDescription());
            more.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            more.setOnClickListener(view -> actions(item));
            more.setFocusable(false);
            row.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            return row;
        }
    }
}
