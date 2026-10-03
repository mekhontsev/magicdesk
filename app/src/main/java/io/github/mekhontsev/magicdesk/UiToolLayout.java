package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native tool composition only: no services, task placement or lifecycle ownership. */
final class UiToolLayout {
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static LinearLayout page(Context context, UiColor color, boolean ime) {
        LinearLayout page = column(context);
        UiAppearance.background(page, color);
        page.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        SystemBarInsets.addToPadding(page, ime);
        return page;
    }

    static LinearLayout column(Context context) {
        var column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    static View actions(LinearLayout row) {
        Context context = row.getContext();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        var scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setMinimumHeight(dp(context, 48));
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        return scroll;
    }

    static ScrollView scroll(View content, int maxWidthDp) {
        var scroll = new ScrollView(content.getContext());
        scroll.setFillViewport(true);
        scroll.addView(new UiContentColumn(content, maxWidthDp), new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    static LinearLayout settingRow(Context context, int icon, int title, TextView detail, View trailing) {
        var row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        row.setMinimumHeight(dp(context, 52));
        UiAppearance.component(row, ShellControls.Role.SETTINGS_ROW);
        if (icon != 0) {
            var image = new ImageView(context);
            UiAppearance.icon(image, icon, UiColor.ACCENT);
            image.setDuplicateParentStateEnabled(true);
            var params = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
            params.setMarginEnd(dp(context, 12)); row.addView(image, params);
        }
        var text = column(context);
        text.setDuplicateParentStateEnabled(true);
        var label = new TextView(context);
        label.setText(title); label.setTextSize(14); label.setDuplicateParentStateEnabled(true);
        UiAppearance.text(label, UiColor.TEXT);
        UiAppearance.componentText(label, ShellControls.Role.SETTINGS_ROW);
        text.addView(label);
        if (detail != null) { detail.setDuplicateParentStateEnabled(true); text.addView(detail); }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) {
            var params = new LinearLayout.LayoutParams(-2, -2);
            params.setMarginStart(dp(context, 12)); row.addView(trailing, params);
        }
        return row;
    }
}
