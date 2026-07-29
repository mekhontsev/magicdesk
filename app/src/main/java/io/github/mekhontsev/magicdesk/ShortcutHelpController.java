package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_CYAN;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL_ALT;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class ShortcutHelpController {
    private final Context mContext;
    private final DesktopUiFactory mUi;
    private final Runnable mHidePanels;
    private final Runnable mOverlayUnavailable;

    private LinearLayout mPanel;

    ShortcutHelpController(
            final Context context,
            final DesktopUiFactory ui,
            final Runnable hidePanels,
            final Runnable overlayUnavailable) {
        mContext = context;
        mUi = ui;
        mHidePanels = hidePanels;
        mOverlayUnavailable = overlayUnavailable;
    }

    LinearLayout createPanel() {
        final LinearLayout panel = new LinearLayout(mContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(mUi.rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);

        final LinearLayout header = new LinearLayout(mContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(mContext);
        title.setText(R.string.shortcuts_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Button close =
                mUi.smallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> mHidePanels.run());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(86), LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addRow(panel, R.string.shortcut_maximize,
                R.string.shortcut_maximize_action);
        addRow(panel, R.string.shortcut_restore,
                R.string.shortcut_restore_action);
        addRow(panel, R.string.shortcut_snap_left,
                R.string.shortcut_snap_left_action);
        addRow(panel, R.string.shortcut_snap_right,
                R.string.shortcut_snap_right_action);
        addRow(panel, R.string.shortcut_close,
                R.string.shortcut_close_action);
        addRow(panel, R.string.shortcut_back,
                R.string.shortcut_back_action);
        addRow(panel, R.string.shortcut_lock,
                R.string.shortcut_lock_action);
        addRow(panel, R.string.shortcut_notifications,
                R.string.shortcut_notifications_action);
        addRow(panel, R.string.shortcut_screenshot,
                R.string.shortcut_screenshot_action);
        addRow(panel, R.string.shortcut_desktop,
                R.string.shortcut_desktop_action);
        addRow(panel, R.string.shortcut_desktop_spaces,
                R.string.shortcut_desktop_spaces_action);
        addRow(panel, R.string.shortcut_help,
                R.string.shortcut_help_action);
        addRow(panel, R.string.shortcut_layout,
                R.string.shortcut_layout_action);
        addRow(panel, R.string.shortcut_previous,
                R.string.shortcut_previous_action);
        addRow(panel, R.string.shortcut_next,
                R.string.shortcut_next_action);
        mPanel = panel;
        return panel;
    }

    void toggle(
            final OverlayPanelController overlays,
            final Rect contentBounds,
            final int taskbarHeight) {
        if (overlays == null || mPanel == null) {
            return;
        }
        if (overlays.isVisible(mPanel)) {
            overlays.hide(mPanel);
            return;
        }
        final int areaWidth = contentBounds.width();
        final int areaHeight = contentBounds.height();
        final int width = Math.min(dp(520), areaWidth - dp(24));
        final int height =
                Math.min(dp(560), areaHeight - taskbarHeight - dp(24));
        final int left = contentBounds.left
                + Math.max(0, (areaWidth - width) / 2);
        final int top = contentBounds.top
                + Math.max(0, (areaHeight - taskbarHeight - height) / 2);
        if (!overlays.show(
                mPanel, left, top, width, height,
                false, "MagicDesk keyboard shortcuts")) {
            mOverlayUnavailable.run();
        }
    }

    private void addRow(
            final LinearLayout panel,
            final int keysResId,
            final int actionResId) {
        final LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        final TextView keys = new TextView(mContext);
        keys.setText(keysResId);
        keys.setTextColor(COLOR_CYAN);
        keys.setTextSize(14);
        keys.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(keys, new LinearLayout.LayoutParams(
                dp(150), LinearLayout.LayoutParams.WRAP_CONTENT));
        final TextView action = new TextView(mContext);
        action.setText(actionResId);
        action.setTextColor(COLOR_TEXT);
        action.setTextSize(14);
        row.addView(action, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
