package io.github.mekhontsev.magicdesk;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Owns the taskbar overflow button and its display-scoped popup. */
final class TaskbarOverflowController {
    interface Listener {
        void onItemSelected(Entry entry);
    }

    static final class Entry {
        final AppItem app;
        final TaskRepository.TaskEntry task;

        Entry(
                final AppItem app,
                final TaskRepository.TaskEntry task) {
            this.app = app;
            this.task = task;
        }
    }

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final Listener mListener;
    private final List<Entry> mItems = new ArrayList<>();

    private ScrollView mPanel;
    private LinearLayout mList;

    TaskbarOverflowController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui,
            final Listener listener) {
        mActivity = activity;
        mUi = ui;
        mListener = listener;
    }

    View createButton(final List<Entry> items) {
        clear();
        mItems.addAll(items);
        final int hiddenCount = mItems.size();

        final FrameLayout button = new FrameLayout(mActivity);
        button.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                desktopDp(10, 8),
                DesktopUiFactory.COLOR_CYAN));
        button.setClickable(true);
        button.setFocusable(true);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(android.R.drawable.ic_menu_more);
        icon.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        icon.setPadding(
                desktopDp(9, 7),
                desktopDp(9, 7),
                desktopDp(9, 7),
                desktopDp(9, 7));
        button.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        final TextView badge = new TextView(mActivity);
        badge.setText(hiddenCount > 99 ? "99+" : Integer.toString(hiddenCount));
        badge.setTextColor(DesktopUiFactory.COLOR_TEXT);
        badge.setTextSize(8);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_RED,
                dp(8),
                DesktopUiFactory.COLOR_RED));
        final FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(16),
                Gravity.TOP | Gravity.END);
        badgeParams.setMargins(0, 0, dp(1), 0);
        button.addView(badge, badgeParams);

        final String description = mActivity.getResources().getQuantityString(
                R.plurals.taskbar_hidden_items_description,
                hiddenCount,
                Integer.valueOf(hiddenCount));
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setOnClickListener(this::toggle);
        return button;
    }

    void clear() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays != null && overlays.isVisible(mPanel)) {
            overlays.hide(mPanel);
        }
        mItems.clear();
    }

    void release() {
        clear();
        mPanel = null;
        mList = null;
    }

    private void toggle(final View anchor) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays == null || mItems.isEmpty()) {
            return;
        }
        if (overlays.isVisible(mPanel)) {
            overlays.hide(mPanel);
            return;
        }
        ensurePanel();
        populate();
        mActivity.captureInteractionStackForPanel();

        final int areaLeft = mActivity.getDesktopAreaLeft();
        final int areaTop = mActivity.getDesktopAreaTop();
        final int areaWidth = mActivity.getDesktopAreaWidth();
        final int rowHeight = desktopDp(54, 44);
        final int width = Math.min(
                desktopDp(300, 230),
                Math.max(1, areaWidth - dp(16)));
        final int maxHeight = Math.max(
                rowHeight,
                mActivity.getDesktopAreaHeight()
                        - mActivity.getTaskbarHeight() - dp(16));
        final int height = Math.min(
                maxHeight,
                mItems.size() * rowHeight + dp(12));
        final int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        final int maxLeft = areaLeft + Math.max(0, areaWidth - width);
        final int left = Math.max(
                areaLeft,
                Math.min(maxLeft, location[0] + anchor.getWidth() - width));
        final int top = Math.max(areaTop, location[1] - height);
        if (!overlays.show(
                mPanel,
                left,
                top,
                width,
                height,
                false,
                "MagicDesk taskbar overflow")) {
            mActivity.setErrorStatus(
                    "OVERLAY-001",
                    mActivity.getString(
                            R.string.status_overlay_panel_unavailable));
        }
    }

    private void ensurePanel() {
        if (mPanel != null) {
            return;
        }
        mList = new LinearLayout(mActivity);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(dp(6), dp(6), dp(6), dp(6));
        mPanel = new ScrollView(mActivity);
        mPanel.setFillViewport(true);
        mPanel.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                desktopDp(8, 6),
                DesktopUiFactory.COLOR_CYAN));
        mPanel.setClickable(true);
        mPanel.addView(mList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
    }

    private void populate() {
        mList.removeAllViews();
        final int rowHeight = desktopDp(54, 44);
        for (final Entry item : mItems) {
            mList.addView(
                    createRow(item),
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            rowHeight));
        }
    }

    private View createRow(final Entry item) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(7), dp(4), dp(7), dp(4));
        row.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                desktopDp(7, 5),
                item.task != null && item.task.active
                        ? DesktopUiFactory.COLOR_AMBER
                        : DesktopUiFactory.COLOR_PANEL_ALT));
        row.setClickable(true);
        row.setFocusable(true);

        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(item.app.icon);
        icon.setPadding(dp(2), dp(2), dp(2), dp(2));
        row.addView(icon, new LinearLayout.LayoutParams(
                desktopDp(38, 30), desktopDp(38, 30)));

        final LinearLayout labels = new LinearLayout(mActivity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(9), 0, 0, 0);
        final TextView name = new TextView(mActivity);
        name.setText(item.app.label);
        name.setTextColor(DesktopUiFactory.COLOR_TEXT);
        name.setTextSize(mActivity.isCompactDesktopPreview() ? 11 : 13);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        if (item.task != null) {
            final TextView state = new TextView(mActivity);
            state.setText(mActivity.getString(
                    R.string.context_task_status,
                    Integer.valueOf(item.task.taskId),
                    mActivity.getString(item.task.isFreeform()
                            ? R.string.badge_window
                            : R.string.badge_fullscreen)));
            state.setTextColor(DesktopUiFactory.COLOR_MUTED);
            state.setTextSize(mActivity.isCompactDesktopPreview() ? 9 : 10);
            state.setSingleLine(true);
            labels.addView(state);
        }
        row.addView(labels, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(view -> mListener.onItemSelected(item));
        mActivity.registerContextTarget(row, item.app, item.task);
        return row;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private int desktopDp(
            final int normalValue,
            final int compactValue) {
        return mUi.desktopDp(
                normalValue,
                compactValue,
                mActivity.isCompactDesktopPreview());
    }
}
