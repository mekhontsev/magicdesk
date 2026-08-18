package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stable tabular presentation for the task manager. */
final class TaskManagerView {
    interface Actions {
        void focus(TaskRepository.TaskEntry task);

        void openLogs(TaskRepository.TaskEntry task);

        void close(TaskRepository.TaskEntry task);

        void forceStop(TaskRepository.TaskEntry task);
    }

    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_SURFACE = 0xFF141B26;
    private static final int COLOR_ACTIVE = 0xFF1F2C3A;
    private static final int COLOR_DIVIDER = 0xFF26303D;
    private static final int COLOR_TEXT = 0xFFE8EEF5;
    private static final int COLOR_MUTED = 0xFF9DAAB8;
    private static final int TABLE_MIN_WIDTH_DP = 820;
    private static final int DISPLAY_WIDTH_DP = 72;
    private static final int MODE_WIDTH_DP = 96;
    private static final int TASK_WIDTH_DP = 72;
    private static final int CPU_WIDTH_DP = 72;
    private static final int MEMORY_WIDTH_DP = 96;
    private static final int ACTIONS_WIDTH_DP = 168;

    private final Activity mActivity;
    private final Actions mActions;
    private final Runnable mRefresh;
    private final LinearLayout mRows;
    private final TextView mStatus;
    private final ImageButton mRefreshButton;
    private final View mRoot;
    private final Map<Integer, TaskRow> mTaskRows = new LinkedHashMap<>();
    private final Map<String, AppResources> mAppResources =
            new LinkedHashMap<>();
    private List<Integer> mOrder = new ArrayList<>();

    TaskManagerView(
            final Activity activity,
            final Runnable refresh,
            final Actions actions) {
        mActivity = activity;
        mRefresh = refresh;
        mActions = actions;
        mRows = new LinearLayout(activity);
        mRows.setOrientation(LinearLayout.VERTICAL);
        mStatus = statusView();
        mRefreshButton = iconButton(
                R.drawable.ic_file_refresh,
                R.string.action_refresh,
                view -> mRefresh.run());
        mRoot = createContent();
    }

    View root() {
        return mRoot;
    }

    void showWaiting() {
        mStatus.setText(R.string.task_manager_waiting);
    }

    void showInitialLoading() {
        mStatus.setText(R.string.task_manager_loading);
    }

    void showUnavailable(final String error) {
        mStatus.setText(mActivity.getString(
                R.string.task_manager_unavailable,
                error == null || error.isEmpty() ? "unknown" : error));
    }

    void render(
            final List<TaskRepository.TaskEntry> sourceTasks,
            final SystemMonitorRepository.Snapshot monitor) {
        final List<TaskRepository.TaskEntry> tasks =
                new ArrayList<>(sourceTasks);
        tasks.sort(Comparator
                .comparingInt((TaskRepository.TaskEntry task) -> task.displayId)
                .thenComparing(
                        task -> resourcesFor(task.packageName).label,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(task -> task.taskId));
        updateRows(tasks, monitor);
        updateSummary(tasks.size(), monitor);
    }

    String labelForPackage(final String packageName) {
        return resourcesFor(packageName).label;
    }

    private View createContent() {
        final LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BACKGROUND);
        page.setPadding(dp(12), dp(8), dp(12), dp(8));
        SystemBarInsets.addToPadding(page);

        final LinearLayout header = new LinearLayout(mActivity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(mActivity);
        title.setText(R.string.task_manager_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(mRefreshButton, new LinearLayout.LayoutParams(
                dp(44), dp(44)));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        mStatus.setText(R.string.task_manager_waiting);
        page.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        final HorizontalScrollView horizontal =
                new HorizontalScrollView(mActivity);
        horizontal.setFillViewport(true);
        horizontal.setHorizontalScrollBarEnabled(true);

        final LinearLayout table = new LinearLayout(mActivity);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setMinimumWidth(dp(TABLE_MIN_WIDTH_DP));
        table.addView(createHeaderRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        final ScrollView vertical = new ScrollView(mActivity);
        vertical.setFillViewport(true);
        vertical.addView(mRows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        table.addView(vertical, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        horizontal.addView(table, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(horizontal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View createHeaderRow() {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(COLOR_SURFACE);
        row.addView(headerCell(
                R.string.task_manager_column_application,
                Gravity.START | Gravity.CENTER_VERTICAL),
                flexibleColumn());
        row.addView(headerCell(
                R.string.task_manager_column_cpu,
                Gravity.END | Gravity.CENTER_VERTICAL),
                fixedColumn(CPU_WIDTH_DP));
        row.addView(headerCell(
                R.string.task_manager_column_memory,
                Gravity.END | Gravity.CENTER_VERTICAL),
                fixedColumn(MEMORY_WIDTH_DP));
        row.addView(headerCell(
                R.string.task_manager_column_display,
                Gravity.CENTER), fixedColumn(DISPLAY_WIDTH_DP));
        row.addView(headerCell(
                R.string.task_manager_column_mode,
                Gravity.CENTER), fixedColumn(MODE_WIDTH_DP));
        row.addView(headerCell(
                R.string.task_manager_column_task,
                Gravity.CENTER), fixedColumn(TASK_WIDTH_DP));
        row.addView(headerCell(
                R.string.task_manager_column_actions,
                Gravity.CENTER), fixedColumn(ACTIONS_WIDTH_DP));
        return row;
    }

    private TextView headerCell(final int text, final int gravity) {
        final TextView cell = new TextView(mActivity);
        cell.setText(text);
        cell.setTextColor(COLOR_MUTED);
        cell.setTextSize(11f);
        cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cell.setGravity(gravity);
        cell.setSingleLine(true);
        cell.setPadding(dp(8), 0, dp(8), 0);
        return cell;
    }

    private TextView statusView() {
        final TextView status = new TextView(mActivity);
        status.setTextColor(COLOR_MUTED);
        status.setTextSize(12f);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        return status;
    }

    private void updateRows(
            final List<TaskRepository.TaskEntry> tasks,
            final SystemMonitorRepository.Snapshot monitor) {
        final Set<Integer> present = new HashSet<>();
        final List<Integer> order = new ArrayList<>();
        boolean rowsChanged = false;
        for (final TaskRepository.TaskEntry task : tasks) {
            final Integer taskId = Integer.valueOf(task.taskId);
            present.add(taskId);
            order.add(taskId);
            TaskRow row = mTaskRows.get(taskId);
            if (row == null || !row.packageName.equals(task.packageName)) {
                row = new TaskRow(task);
                mTaskRows.put(taskId, row);
                rowsChanged = true;
            }
            row.bind(task, monitor.forPackage(task.packageName));
        }
        if (mTaskRows.keySet().removeIf(
                taskId -> !present.contains(taskId))) {
            rowsChanged = true;
        }
        if (rowsChanged || !order.equals(mOrder)) {
            mRows.removeAllViews();
            for (final Integer taskId : order) {
                mRows.addView(mTaskRows.get(taskId).container,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            mOrder = order;
        }
    }

    private void updateSummary(
            final int taskCount,
            final SystemMonitorRepository.Snapshot monitor) {
        if (!monitor.available) {
            mStatus.setText(mActivity.getString(
                    R.string.task_manager_count, taskCount));
            return;
        }
        final long usedMemory = Math.max(
                0L, monitor.totalMemoryKb - monitor.availableMemoryKb);
        mStatus.setText(mActivity.getString(
                R.string.task_manager_summary,
                taskCount,
                formatPercent(monitor.cpuPercent),
                formatMemory(usedMemory),
                formatMemory(monitor.totalMemoryKb),
                monitor.loadAverage));
    }

    private AppResources resourcesFor(final String packageName) {
        AppResources resources = mAppResources.get(packageName);
        if (resources != null) {
            return resources;
        }
        String label = packageName;
        Drawable icon = mActivity.getDrawable(R.drawable.ic_magicdesk);
        try {
            final ApplicationInfo info = mActivity.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            label = mActivity.getPackageManager()
                    .getApplicationLabel(info).toString();
            icon = mActivity.getPackageManager().getApplicationIcon(info);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep the package name and fallback icon for a disappearing app.
        }
        resources = new AppResources(label, icon);
        mAppResources.put(packageName, resources);
        return resources;
    }

    private String formatPercent(final float value) {
        return value < 0f
                ? "--"
                : String.format(Locale.getDefault(), "%.1f%%", value);
    }

    private String formatMemory(final long valueKb) {
        return valueKb < 0L
                ? "--"
                : Formatter.formatShortFileSize(mActivity, valueKb * 1024L);
    }

    private ImageButton iconButton(
            final int drawable,
            final int description,
            final View.OnClickListener listener) {
        final ImageButton button = new ImageButton(mActivity);
        button.setImageResource(drawable);
        button.setImageTintList(new ColorStateList(
                new int[][]{new int[0]}, new int[]{COLOR_TEXT}));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setBackground(new ColorDrawable(COLOR_SURFACE));
        button.setContentDescription(mActivity.getString(description));
        button.setTooltipText(mActivity.getString(description));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams flexibleColumn() {
        return new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private LinearLayout.LayoutParams fixedColumn(final int widthDp) {
        return new LinearLayout.LayoutParams(
                dp(widthDp), ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(final int value) {
        return Math.round(value
                * mActivity.getResources().getDisplayMetrics().density);
    }

    private final class TaskRow {
        final String packageName;
        final LinearLayout container;
        final LinearLayout row;
        final TextView display;
        final TextView mode;
        final TextView taskId;
        final TextView cpu;
        final TextView memory;
        TaskRepository.TaskEntry task;

        TaskRow(final TaskRepository.TaskEntry initialTask) {
            packageName = initialTask.packageName;
            container = new LinearLayout(mActivity);
            container.setOrientation(LinearLayout.VERTICAL);
            row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(3), dp(4), dp(3));
            row.setOnClickListener(view -> mActions.focus(task));

            row.addView(createIdentity(initialTask), flexibleColumn());
            cpu = valueCell(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(cpu, fixedColumn(CPU_WIDTH_DP));
            memory = valueCell(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(memory, fixedColumn(MEMORY_WIDTH_DP));
            display = valueCell(Gravity.CENTER);
            row.addView(display, fixedColumn(DISPLAY_WIDTH_DP));
            mode = valueCell(Gravity.CENTER);
            row.addView(mode, fixedColumn(MODE_WIDTH_DP));
            taskId = valueCell(Gravity.CENTER);
            row.addView(taskId, fixedColumn(TASK_WIDTH_DP));
            row.addView(createActions(initialTask),
                    fixedColumn(ACTIONS_WIDTH_DP));

            container.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
            final View divider = new View(mActivity);
            divider.setBackgroundColor(COLOR_DIVIDER);
            container.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        void bind(
                final TaskRepository.TaskEntry currentTask,
                final SystemMonitorRepository.ProcessResources resources) {
            task = currentTask;
            row.setBackgroundColor(
                    currentTask.active ? COLOR_ACTIVE : COLOR_BACKGROUND);
            display.setText(Integer.toString(currentTask.displayId));
            mode.setText(currentTask.windowingMode);
            taskId.setText(Integer.toString(currentTask.taskId));
            cpu.setText(formatPercent(resources.cpuPercent));
            memory.setText(formatMemory(resources.pssKb));
        }

        private View createIdentity(final TaskRepository.TaskEntry value) {
            final AppResources resources = resourcesFor(value.packageName);
            final LinearLayout identity = new LinearLayout(mActivity);
            identity.setGravity(Gravity.CENTER_VERTICAL);
            identity.setPadding(dp(6), 0, dp(8), 0);

            final ImageView icon = new ImageView(mActivity);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setImageDrawable(resources.icon);
            identity.addView(icon, new LinearLayout.LayoutParams(
                    dp(36), dp(36)));

            final LinearLayout labels = new LinearLayout(mActivity);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(8), 0, 0, 0);
            final TextView name = new TextView(mActivity);
            name.setText(resources.label);
            name.setTextColor(COLOR_TEXT);
            name.setTextSize(14f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            final TextView packageNameView = new TextView(mActivity);
            packageNameView.setText(value.packageName);
            packageNameView.setTextColor(COLOR_MUTED);
            packageNameView.setTextSize(10f);
            packageNameView.setSingleLine(true);
            packageNameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            labels.addView(name);
            labels.addView(packageNameView);
            identity.addView(labels, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return identity;
        }

        private View createActions(final TaskRepository.TaskEntry value) {
            final LinearLayout actions = new LinearLayout(mActivity);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.addView(iconButton(
                    android.R.drawable.ic_menu_view,
                    R.string.task_manager_focus,
                    view -> mActions.focus(task)), square(40));
            actions.addView(iconButton(
                    android.R.drawable.ic_menu_info_details,
                    R.string.task_manager_logs,
                    view -> mActions.openLogs(task)), square(40));
            actions.addView(iconButton(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    R.string.task_manager_close,
                    view -> mActions.close(task)), square(40));
            if (!BuildConfig.APPLICATION_ID.equals(value.packageName)) {
                actions.addView(iconButton(
                        android.R.drawable.ic_menu_delete,
                        R.string.task_manager_force_stop,
                        view -> mActions.forceStop(task)), square(40));
            }
            return actions;
        }

        private TextView valueCell(final int gravity) {
            final TextView cell = new TextView(mActivity);
            cell.setTextColor(COLOR_TEXT);
            cell.setTextSize(12f);
            cell.setGravity(gravity);
            cell.setSingleLine(true);
            cell.setEllipsize(TextUtils.TruncateAt.END);
            cell.setPadding(dp(8), 0, dp(8), 0);
            return cell;
        }

        private LinearLayout.LayoutParams square(final int sizeDp) {
            return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        }
    }

    private static final class AppResources {
        final String label;
        final Drawable icon;

        AppResources(final String label, final Drawable icon) {
            this.label = label;
            this.icon = icon;
        }
    }
}
