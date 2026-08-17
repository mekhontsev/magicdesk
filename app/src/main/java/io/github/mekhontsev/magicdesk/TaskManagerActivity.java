package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TaskManagerActivity extends Activity
        implements ShellAccess.StateListener {
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_SURFACE = 0xFF141B26;
    private static final int COLOR_TEXT = 0xFFE8EEF5;
    private static final int COLOR_MUTED = 0xFF9DAAB8;
    private static final int COLOR_CYAN = 0xFF22D3EE;

    private LinearLayout mTasks;
    private TextView mStatus;
    private boolean mDestroyed;
    private int mLoadGeneration;

    static Intent createIntent(final Context context) {
        return new Intent(context, TaskManagerActivity.class);
    }

    static AppLaunchTarget launchTarget() {
        return BuiltInDesktopAppCatalog.taskManagerTarget();
    }

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        DesktopTaskDescription.apply(
                this,
                R.string.task_manager_title,
                R.drawable.ic_magicdesk);
        BuiltInWindowRegistry.register(this);
        setContentView(createContent());
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShellAccess.addStateListener(this);
    }

    @Override
    protected void onStop() {
        ShellAccess.removeStateListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mLoadGeneration++;
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (mDestroyed) {
                return;
            }
            if (snapshot != null && snapshot.isReady()) {
                refresh();
            } else {
                mStatus.setText(getString(
                        R.string.task_manager_unavailable,
                        snapshot == null ? "unknown" : snapshot.error));
            }
        });
    }

    private View createContent() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(COLOR_BACKGROUND);
        page.setPadding(dp(12), dp(8), dp(12), dp(8));
        SystemBarInsets.addToPadding(page);

        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        title.setText(R.string.task_manager_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(iconButton(
                R.drawable.ic_file_refresh,
                R.string.action_refresh,
                view -> refresh()), new LinearLayout.LayoutParams(
                dp(44), dp(44)));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        mStatus = new TextView(this);
        mStatus.setTextColor(COLOR_MUTED);
        mStatus.setTextSize(12f);
        page.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        mTasks = new LinearLayout(this);
        mTasks.setOrientation(LinearLayout.VERTICAL);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(mTasks, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void refresh() {
        if (!ShellAccess.isReady()) {
            mStatus.setText(R.string.task_manager_waiting);
            return;
        }
        final int generation = ++mLoadGeneration;
        mStatus.setText(R.string.task_manager_loading);
        TaskRepository.load(-1, snapshot -> runOnUiThread(() -> {
            if (mDestroyed || generation != mLoadGeneration) {
                return;
            }
            if (!snapshot.available) {
                mStatus.setText(getString(
                        R.string.task_manager_unavailable,
                        snapshot.error));
                return;
            }
            render(snapshot.tasks);
        }));
    }

    private void render(final List<TaskRepository.TaskEntry> rawTasks) {
        final List<TaskRepository.TaskEntry> tasks = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : rawTasks) {
            if (DesktopManagedTaskPolicy.isManagedApplicationTask(task)
                    && task.taskId != getTaskId()) {
                tasks.add(task);
            }
        }
        tasks.sort(Comparator
                .comparingInt((TaskRepository.TaskEntry task) -> task.displayId)
                .thenComparing(task -> labelFor(task.packageName))
                .thenComparingInt(task -> task.taskId));
        mTasks.removeAllViews();
        for (final TaskRepository.TaskEntry task : tasks) {
            mTasks.addView(createTaskRow(task), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
            final View divider = new View(this);
            divider.setBackgroundColor(0xFF26303D);
            mTasks.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        mStatus.setText(getString(R.string.task_manager_count, tasks.size()));
    }

    private View createTaskRow(final TaskRepository.TaskEntry task) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(4), dp(4));
        row.setBackgroundColor(task.active ? 0xFF1F2C3A : COLOR_BACKGROUND);
        row.setOnClickListener(view -> focus(task));

        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        try {
            icon.setImageDrawable(getPackageManager()
                    .getApplicationIcon(task.packageName));
        } catch (PackageManager.NameNotFoundException error) {
            icon.setImageResource(R.drawable.ic_magicdesk);
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        final LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, dp(6), 0);
        final TextView name = new TextView(this);
        name.setText(labelFor(task.packageName));
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(15f);
        name.setSingleLine(true);
        final TextView detail = new TextView(this);
        detail.setText(getString(
                R.string.task_manager_task_detail,
                task.displayId,
                task.windowingMode,
                task.taskId,
                task.packageName));
        detail.setTextColor(COLOR_MUTED);
        detail.setTextSize(11f);
        detail.setSingleLine(true);
        labels.addView(name);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        row.addView(iconButton(
                android.R.drawable.ic_menu_view,
                R.string.task_manager_focus,
                view -> focus(task)), square());
        row.addView(iconButton(
                android.R.drawable.ic_menu_info_details,
                R.string.task_manager_logs,
                view -> openLogs(task)), square());
        row.addView(iconButton(
                android.R.drawable.ic_menu_close_clear_cancel,
                R.string.task_manager_close,
                view -> closeTask(task)), square());
        if (!BuildConfig.APPLICATION_ID.equals(task.packageName)) {
            row.addView(iconButton(
                    android.R.drawable.ic_menu_delete,
                    R.string.task_manager_force_stop,
                    view -> confirmForceStop(task)), square());
        }
        return row;
    }

    private void focus(final TaskRepository.TaskEntry task) {
        TaskRepository.bringToFront(task, this::showActionResult);
    }

    private void closeTask(final TaskRepository.TaskEntry task) {
        TaskRepository.closeTask(task, result -> {
            showActionResult(result);
            if (result.success) {
                runOnUiThread(this::refresh);
            }
        });
    }

    private void confirmForceStop(final TaskRepository.TaskEntry task) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.task_manager_force_stop)
                .setMessage(getString(
                        R.string.task_manager_force_stop_message,
                        labelFor(task.packageName)))
                .setPositiveButton(R.string.task_manager_force_stop,
                        (dialog, which) -> TaskRepository.forceStop(
                                task.packageName,
                                result -> {
                                    showActionResult(result);
                                    if (result.success) {
                                        runOnUiThread(this::refresh);
                                    }
                                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openLogs(final TaskRepository.TaskEntry task) {
        BuiltInWindowLauncher.launch(
                this,
                AppLogViewerActivity.createIntent(
                        this,
                        task.packageName,
                        labelFor(task.packageName)),
                AppLogViewerActivity.launchTarget(),
                error -> {
                    if (error != null) {
                        Toast.makeText(
                                this,
                                getString(
                                        R.string.task_manager_action_failed,
                                        ShellAccess.usefulMessage(error)),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showActionResult(final TaskRepository.ActionResult result) {
        runOnUiThread(() -> {
            if (!mDestroyed && !result.success) {
                Toast.makeText(
                        this,
                        getString(
                                R.string.task_manager_action_failed,
                                result.message),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private String labelFor(final String packageName) {
        try {
            final ApplicationInfo info = getPackageManager()
                    .getApplicationInfo(packageName, 0);
            return getPackageManager().getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException error) {
            return packageName;
        }
    }

    private ImageButton iconButton(
            final int drawable,
            final int description,
            final View.OnClickListener listener) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(drawable);
        button.setImageTintList(new ColorStateList(
                new int[][]{new int[0]}, new int[]{COLOR_TEXT}));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(new ColorDrawable(COLOR_SURFACE));
        button.setContentDescription(getString(description));
        button.setTooltipText(getString(description));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams square() {
        return new LinearLayout.LayoutParams(dp(44), dp(44));
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
