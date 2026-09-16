package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Control Panel's ordinary Android tasks, never the Desktop switcher. */
final class IndependentApplicationsDialog {
    private record Entry(TaskRepository.TaskEntry task, AppItem app, String title, String detail) { }
    private interface Loaded { void complete(List<Entry> entries, Throwable error); }

    private final Activity activity;
    private final DesktopDisplayInfo display;
    private final Runnable changed;
    private final DesktopUiFactory ui;
    private final WindowsAdapter adapter = new WindowsAdapter();
    private final AlertDialog dialog;
    private List<Entry> items;
    private boolean busy;

    private IndependentApplicationsDialog(Activity activity, DesktopDisplayInfo display,
            Runnable changed, List<Entry> items) {
        this.activity = activity;
        this.display = display;
        this.changed = changed;
        this.items = items;
        ui = new DesktopUiFactory(activity);
        dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.display_independent_apps) + " [" + display.id + "]")
                .setAdapter(adapter, null)
                .setNegativeButton(android.R.string.cancel, null).create();
    }

    static void show(Activity activity, DesktopDisplayInfo display, Runnable changed) {
        load(activity, display, (items, error) -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (error != null) { failure(activity, error); return; }
            final var picker = new IndependentApplicationsDialog(activity, display, changed, items);
            picker.dialog.show();
            picker.dialog.getListView().setOnItemClickListener((parent, row, position, id) -> {
                if (position < picker.items.size()) picker.control(picker.items.get(position), false);
            });
        });
    }

    private static void load(Activity activity, DesktopDisplayInfo display, Loaded loaded) {
        TaskCommandQueue.execute(() -> {
            try {
                DesktopDisplayCatalog.require(display.id, display.uniqueId);
                final var snapshot = ApplicationTaskPlacement.independentSnapshot(TaskRepository.loadAllNow(),
                        display.id, DesktopRuntimeBridge.hasWorkspace(display.id));
                if (!snapshot.available) { throw new IOException(snapshot.error); }
                final List<Entry> items = entries(activity, snapshot.tasks);
                activity.runOnUiThread(() -> loaded.complete(items, null));
            } catch (IOException | RuntimeException error) {
                activity.runOnUiThread(() -> loaded.complete(List.of(), error));
            }
        });
    }

    private static List<Entry> entries(Activity activity, List<TaskRepository.TaskEntry> tasks) {
        final LauncherAppRepository repository = new LauncherAppRepository(activity);
        final List<AppItem> known = new ArrayList<>();
        final List<Entry> entries = new ArrayList<>();
        for (final var task : tasks) {
            if (!repository.owns(task)) continue;
            final AppIdentity application = repository.profile().application(task);
            final AppReference reference = AppReference.forTask(application, task);
            final AppItem app = reference == null ? null : repository.findOrLoad(
                    known, application, reference.launchTarget(), true);
            if (app != null && !known.contains(app)) known.add(app);
            entries.add(new Entry(task, BuiltInWindowRegistry.present(activity, app, task),
                    TaskTitle.resolve(activity, app, task), TaskTitle.detail(activity, task)));
        }
        return entries;
    }

    private boolean alive() { return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }

    private void control(Entry item, boolean close) {
        if (busy || !alive()) return;
        busy = true;
        adapter.notifyDataSetChanged();
        ApplicationTaskPlacement.controlIndependent(item.task(), display.uniqueId, close,
                result -> activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    changed.run();
                    if (!alive()) return;
                    if (result.success && !close) { dialog.dismiss(); return; }
                    if (!result.success) failure(activity, new IOException(result.message));
                    load(activity, display, (entries, error) -> {
                        if (!alive()) return;
                        busy = false;
                        if (error != null) failure(activity, error);
                        else items = entries;
                        adapter.notifyDataSetChanged();
                    });
                }));
    }

    private String detail(Entry item) {
        if (items.stream().filter(other -> other.title().equals(item.title())).limit(2).count() < 2) {
            return item.detail();
        }
        final String id = activity.getString(R.string.independent_window_id, item.task().taskId);
        return item.detail().isEmpty() ? id : item.detail() + " | " + id;
    }

    private final class WindowsAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, items.size()); }
        @Override public Object getItem(int position) { return items.isEmpty() ? null : items.get(position); }
        @Override public long getItemId(int position) { return items.isEmpty() ? -1 : items.get(position).task().taskId; }
        @Override public boolean hasStableIds() { return true; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return !busy && !items.isEmpty(); }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            if (items.isEmpty()) {
                final TextView empty = new TextView(activity);
                empty.setText(R.string.display_no_independent_apps);
                empty.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(16));
                return empty;
            }
            final Entry item = items.get(position);
            final LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ui.dp(16), ui.dp(8), ui.dp(8), ui.dp(8));
            row.setMinimumHeight(ui.dp(72));
            final ImageView icon = new ImageView(activity);
            icon.setImageDrawable(item.app() == null ? activity.getPackageManager().getDefaultActivityIcon() : item.app().icon);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
            final LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(ui.dp(12), 0, ui.dp(8), 0);
            final TextView title = new TextView(activity);
            title.setText(item.title());
            title.setTextColor(DesktopUiFactory.COLOR_TEXT);
            title.setTextSize(16);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            final String hint = detail(item);
            if (!hint.isEmpty()) {
                final TextView detail = new TextView(activity);
                detail.setText(hint);
                detail.setTextSize(12);
                detail.setTextColor(DesktopUiFactory.COLOR_MUTED);
                detail.setMaxLines(2);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                labels.addView(detail);
            }
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.setAlpha(busy ? 0.5f : 1f);
            final ImageButton close = ui.menuIconButton(R.drawable.ic_close, R.string.action_close_window);
            close.setContentDescription(activity.getString(R.string.independent_close_window, item.title()));
            close.setTooltipText(close.getContentDescription());
            close.setEnabled(!busy);
            close.setFocusable(false);
            close.setOnClickListener(view -> control(item, true));
            row.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            return row;
        }
    }

    private static void failure(Activity activity, Throwable error) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            Toast.makeText(activity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
        }
    }
}
