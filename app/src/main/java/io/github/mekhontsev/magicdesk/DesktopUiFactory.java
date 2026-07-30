package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

final class DesktopUiFactory {
    static final int COLOR_BACKGROUND = 0xFF090D14;
    static final int COLOR_PANEL = 0xFF111827;
    static final int COLOR_PANEL_ALT = 0xFF172033;
    static final int COLOR_TEXT = 0xFFE5E7EB;
    static final int COLOR_MUTED = 0xFF94A3B8;
    static final int COLOR_CYAN = 0xFF22D3EE;
    static final int COLOR_RED = 0xFFF43F5E;
    static final int COLOR_AMBER = 0xFFF59E0B;

    private final Context mContext;

    DesktopUiFactory(final Context context) {
        mContext = context;
    }

    int dp(final int value) {
        return Math.round(value
                * mContext.getResources().getDisplayMetrics().density);
    }

    int desktopDp(
            final int normalValue,
            final int compactValue,
            final boolean compact) {
        return dp(compact ? compactValue : normalValue);
    }

    TextView sectionTitle(final int titleResId) {
        final TextView title = new TextView(mContext);
        title.setText(titleResId);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    Button actionButton(final int textResId, final int accentColor) {
        return actionButton(mContext.getString(textResId), accentColor);
    }

    Button actionButton(final String text, final int accentColor) {
        final Button button = new Button(mContext);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(new ColorStateList(
                new int[][] {
                    new int[] {-android.R.attr.state_enabled},
                    new int[0]
                },
                new int[] {COLOR_MUTED, Color.WHITE}));
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        final StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[] {-android.R.attr.state_enabled},
                rounded(COLOR_PANEL, dp(10), COLOR_MUTED));
        background.addState(
                new int[0],
                rounded(COLOR_PANEL_ALT, dp(10), accentColor));
        button.setBackground(background);
        return button;
    }

    Button smallButton(final int textResId, final int accentColor) {
        return smallButton(mContext.getString(textResId), accentColor);
    }

    Button smallButton(final String text, final int accentColor) {
        final Button button = actionButton(text, accentColor);
        button.setTextSize(11);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(4), dp(2), dp(4), dp(2));
        return button;
    }

    ImageButton taskbarIconButton(
            final int drawableResId,
            final int descriptionResId,
            final boolean compact) {
        final ImageButton button = new ImageButton(mContext);
        button.setImageResource(drawableResId);
        button.setColorFilter(COLOR_TEXT);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(rounded(
                COLOR_PANEL_ALT,
                desktopDp(8, 6, compact),
                COLOR_PANEL_ALT));
        button.setContentDescription(mContext.getString(descriptionResId));
        button.setTooltipText(mContext.getString(descriptionResId));
        return button;
    }

    GradientDrawable rounded(
            final int color,
            final int radius,
            final int strokeColor) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }
}
