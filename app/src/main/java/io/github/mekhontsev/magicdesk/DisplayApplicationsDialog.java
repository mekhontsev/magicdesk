package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Tools and existing Android tasks share a destination, not a launch mechanism. */
final class DisplayApplicationsDialog {
    private record Item(String title, String detail, String tool, TaskRepository.TaskEntry task) { }
    private final Activity activity;
    private final DesktopDisplayInfo display;
    private final List<Item> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private final AlertDialog dialog;

    private DisplayApplicationsDialog(Activity activity, DesktopDisplayInfo display) {
        this.activity = activity;
        this.display = display;
        items.add(new Item(activity.getString(R.string.control_section_tools), "", null, null));
        items.add(new Item(activity.getString(R.string.file_manager_title), "", "files", null));
        items.add(new Item(activity.getString(R.string.terminal_sessions), "", "sessions", null));
        items.add(new Item(activity.getString(R.string.action_settings), "", "settings", null));
        items.add(new Item(activity.getString(R.string.display_running_apps), "", null, null));
        dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.display_open_on, display.name))
                .setAdapter(adapter, (picker, index) -> open(items.get(index)))
                .setNegativeButton(android.R.string.cancel, null).create();
    }

    static void show(Activity activity, DesktopDisplayInfo display) {
        final var picker = new DisplayApplicationsDialog(activity, display);
        picker.dialog.show();
        TaskCommandQueue.execute(() -> {
            final var snapshot = TaskRepository.loadAllNow();
            final int userId = AppProfile.current(activity).userId;
            final List<Item> tasks = new ArrayList<>();
            for (var task : snapshot.tasks) {
                if (task.userId != userId || !TaskRepository.isTransferable(task)) continue;
                String label = task.packageName;
                try {
                    final var pm = activity.getPackageManager();
                    label = pm.getApplicationLabel(pm.getApplicationInfo(task.packageName, 0)).toString();
                    if (BuildConfig.APPLICATION_ID.equals(task.packageName)) {
                        final var component = android.content.ComponentName.unflattenFromString(task.componentName);
                        if (component != null) { label = pm.getActivityInfo(component, 0).loadLabel(pm).toString(); }
                    }
                } catch (PackageManager.NameNotFoundException ignored) { }
                final var terminal = ConsoleTerminalRegistry.snapshotForTask(task.taskId);
                if (terminal != null) { label = terminal.taskLabel(label); }
                tasks.add(new Item(label, activity.getString(task.displayId == display.id
                        ? R.string.display_show_task : R.string.display_move_task, task.displayId, task.taskId), null, task));
            }
            activity.runOnUiThread(() -> {
                if (!picker.alive()) return;
                if (!snapshot.available) picker.items.add(new Item(snapshot.error, "", null, null));
                else if (tasks.isEmpty()) picker.items.add(new Item(activity.getString(R.string.display_no_tasks), "", null, null));
                else picker.items.addAll(tasks);
                picker.adapter.notifyDataSetChanged();
            });
        });
    }

    private boolean alive() { return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }

    private void open(Item item) {
        try {
            final var target = ToolLaunchTarget.resolve("auto", display.id, MagicDeskRuntime.activeDesktopDisplayId());
            if (item.task != null) {
                TaskRepository.moveTaskToDisplay(item.task, display.id, null, result -> {
                    if (!result.success) fail(new IllegalStateException(result.message));
                });
            } else if ("sessions".equals(item.tool)) {
                TerminalSessionsDialog.show(activity, target, display.uniqueId);
            } else if (item.tool != null) {
                ToolApplications.open(activity, ToolApplications.intent(activity, item.tool), target,
                        display.uniqueId, this::fail);
            }
        } catch (RuntimeException error) { fail(error); }
    }

    private void fail(Throwable error) {
        if (error != null) activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed())
                Toast.makeText(activity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) {
            final var item = items.get(position);
            return item.tool != null || item.task != null;
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            final var item = items.get(position);
            final var ui = new DesktopUiFactory(activity);
            final var row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(10));
            final var title = new TextView(activity);
            title.setText(item.title);
            title.setTextSize(isEnabled(position) ? 16 : 13);
            title.setMaxLines(2);
            row.addView(title);
            if (!item.detail.isEmpty()) {
                final var detail = new TextView(activity);
                detail.setText(item.detail); detail.setTextSize(12);
                row.addView(detail);
            }
            return row;
        }
    }
}
