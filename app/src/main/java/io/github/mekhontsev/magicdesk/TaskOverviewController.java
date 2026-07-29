package io.github.mekhontsev.magicdesk;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class TaskOverviewController {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private LinearLayout mPanel;

    TaskOverviewController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    LinearLayout create() {
        final LinearLayout panel = new LinearLayout(mActivity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(12));
        panel.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                dp(8),
                DesktopUiFactory.COLOR_CYAN));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.setFocusable(true);
        mPanel = panel;
        return panel;
    }

    boolean isVisible() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        return overlays != null && overlays.isVisible(mPanel);
    }

    void toggle() {
        mActivity.resetAltTabState();
        if (isVisible()) {
            mActivity.hideAllPanels();
            return;
        }
        show();
    }

    void show() {
        mActivity.resetAltTabState();
        mActivity.captureInteractionStackForPanel();
        mActivity.hideAllPanels();
        final int displayId = mActivity.getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot ->
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()
                            || displayId != mActivity.getCurrentDisplayId()) {
                        return;
                    }
                    mActivity.setTaskSnapshot(snapshot);
                    populate(snapshot);
                    showPanel();
                }));
    }

    void populate(final TaskRepository.Snapshot snapshot) {
        if (mPanel == null) {
            return;
        }
        mPanel.removeAllViews();

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(mActivity);
        final List<TaskRepository.TaskEntry> tasks = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (mActivity.isTaskbarTask(task)) {
                tasks.add(task);
            }
        }
        title.setText(mActivity.getString(
                R.string.open_tasks_title,
                Integer.valueOf(tasks.size())));
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Button showDesktop = mUi.smallButton(
                R.string.action_show_desktop,
                DesktopUiFactory.COLOR_PANEL_ALT);
        showDesktop.setOnClickListener(view ->
                mActivity.toggleDesktopWorkspace());
        header.addView(showDesktop, new LinearLayout.LayoutParams(
                dp(120), LinearLayout.LayoutParams.WRAP_CONTENT));

        final Button close = mUi.smallButton(
                R.string.action_close,
                DesktopUiFactory.COLOR_PANEL_ALT);
        close.setOnClickListener(view -> mActivity.hideAllPanels());
        final LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(
                        dp(82), LinearLayout.LayoutParams.WRAP_CONTENT);
        closeParams.setMargins(dp(8), 0, 0, 0);
        header.addView(close, closeParams);
        mPanel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (tasks.isEmpty()) {
            final TextView empty = new TextView(mActivity);
            empty.setText(R.string.open_tasks_empty);
            empty.setTextColor(DesktopUiFactory.COLOR_MUTED);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            mPanel.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return;
        }

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        final GridLayout grid = new GridLayout(mActivity);
        final int columns =
                mActivity.getResources().getConfiguration().screenWidthDp >= 900
                        ? 4 : 3;
        grid.setColumnCount(columns);
        for (final TaskRepository.TaskEntry task : tasks) {
            final AppItem app = mActivity.findOrLoadApp(
                    mActivity.getLauncherApps(), task.packageName);
            if (app == null) {
                continue;
            }
            grid.addView(
                    createTaskTile(
                            app,
                            task,
                            mActivity.isAltTabTaskSelected(task)),
                    createTileParams());
        }
        scroll.addView(grid, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.setMargins(0, dp(12), 0, 0);
        mPanel.addView(scroll, scrollParams);
    }

    boolean showPanel() {
        final int areaWidth = mActivity.getDesktopAreaWidth();
        final int areaHeight = mActivity.getDesktopAreaHeight();
        final int width = Math.min(dp(760), areaWidth - dp(32));
        final int height = Math.min(
                dp(520),
                areaHeight - mActivity.getTaskbarHeight() - dp(32));
        final int left = mActivity.getDesktopAreaLeft()
                + Math.max(0, (areaWidth - width) / 2);
        final int top = mActivity.getDesktopAreaTop() + Math.max(
                0,
                (areaHeight - mActivity.getTaskbarHeight() - height) / 2);
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null && overlays.show(
                mPanel,
                left,
                top,
                width,
                height,
                true,
                "MagicDesk open tasks")) {
            return true;
        }
        mActivity.setErrorStatus(
                "OVERLAY-001",
                mActivity.getString(R.string.status_overlay_panel_unavailable));
        return false;
    }

    private View createTaskTile(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final boolean selected) {
        final FrameLayout tile = new FrameLayout(mActivity);
        final boolean workspaceApp =
                mActivity.isWorkspaceApp(app.packageName);
        tile.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(6),
                selected || workspaceApp
                        ? DesktopUiFactory.COLOR_AMBER
                        : (task.active
                                ? DesktopUiFactory.COLOR_CYAN
                                : DesktopUiFactory.COLOR_PANEL_ALT)));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            mActivity.resetAltTabState();
            mActivity.hideAllPanels();
            mActivity.focusTask(app, task);
        });
        mActivity.registerContextTarget(tile, app, task);

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(8), dp(8), dp(8), dp(6));
        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        content.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        final TextView label = new TextView(mActivity);
        label.setText(app.label);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(12);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(0, dp(5), 0, 0);
        content.addView(label, labelParams);

        final TextView state = new TextView(mActivity);
        state.setText(mActivity.getString(
                R.string.context_task_status,
                Integer.valueOf(task.taskId),
                mActivity.getString(task.isFreeform()
                        ? R.string.badge_window
                        : R.string.badge_fullscreen)));
        state.setTextColor(DesktopUiFactory.COLOR_MUTED);
        state.setTextSize(10);
        state.setGravity(Gravity.CENTER);
        content.addView(state, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tile.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        final ImageButton close = new ImageButton(mActivity);
        close.setImageResource(
                android.R.drawable.ic_menu_close_clear_cancel);
        close.setColorFilter(DesktopUiFactory.COLOR_MUTED);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setPadding(dp(5), dp(5), dp(5), dp(5));
        close.setContentDescription(
                mActivity.getString(R.string.action_close_window));
        close.setOnClickListener(view -> mActivity.closeTask(app, task));
        final FrameLayout.LayoutParams closeParams =
                new FrameLayout.LayoutParams(
                        dp(32), dp(32), Gravity.TOP | Gravity.END);
        tile.addView(close, closeParams);
        return tile;
    }

    private GridLayout.LayoutParams createTileParams() {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(112);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
