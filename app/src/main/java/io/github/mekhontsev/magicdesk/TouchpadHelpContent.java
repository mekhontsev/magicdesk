package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class TouchpadHelpContent {
    private static final int KEYS_WIDTH_DP = 142;

    private TouchpadHelpContent() {
    }

    static ScrollView create(
            final Context context,
            final DesktopUiFactory ui) {
        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClickable(true);
        scroll.setBackground(ui.rounded(
                DesktopUiFactory.COLOR_PANEL,
                ui.dp(8),
                DesktopUiFactory.COLOR_PANEL_ALT));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        addSectionTitle(
                context, ui, content,
                R.string.touchpad_gestures_title, 0);
        addRow(context, ui, content, R.string.touchpad_gesture_move,
                R.string.touchpad_gesture_move_action);
        addRow(context, ui, content, R.string.touchpad_gesture_click,
                R.string.touchpad_gesture_click_action);
        addRow(context, ui, content, R.string.touchpad_gesture_right_click,
                R.string.touchpad_gesture_right_click_action);
        addRow(context, ui, content, R.string.touchpad_gesture_drag,
                R.string.touchpad_gesture_drag_action);
        addRow(context, ui, content, R.string.touchpad_gesture_scroll,
                R.string.touchpad_gesture_scroll_action);
        addRow(context, ui, content, R.string.touchpad_gesture_keyboard,
                R.string.touchpad_gesture_keyboard_action);

        addSectionTitle(
                context, ui, content,
                R.string.shortcuts_title, ui.dp(18));
        for (final ShortcutCatalog.Entry entry : ShortcutCatalog.ENTRIES) {
            addRow(context, ui, content,
                    entry.keysResId, entry.actionResId);
        }
        return scroll;
    }

    private static void addSectionTitle(
            final Context context,
            final DesktopUiFactory ui,
            final LinearLayout content,
            final int titleResId,
            final int topMargin) {
        final TextView title = new TextView(context);
        title.setText(titleResId);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        params.bottomMargin = ui.dp(6);
        content.addView(title, params);
    }

    private static void addRow(
            final Context context,
            final DesktopUiFactory ui,
            final LinearLayout content,
            final int keysResId,
            final int actionResId) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(7), 0, ui.dp(7));

        final TextView keys = new TextView(context);
        keys.setText(keysResId);
        keys.setTextColor(DesktopUiFactory.COLOR_CYAN);
        keys.setTextSize(14);
        keys.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(keys, new LinearLayout.LayoutParams(
                ui.dp(KEYS_WIDTH_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView action = new TextView(context);
        action.setText(actionResId);
        action.setTextColor(DesktopUiFactory.COLOR_TEXT);
        action.setTextSize(14);
        row.addView(action, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        content.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }
}
