package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

public final class DesktopUiFactory {
    static final int COLOR_BACKGROUND = 0xFF090D14;
    static final int COLOR_PANEL = 0xFF111827;
    public static final int COLOR_PANEL_ALT = 0xFF172033;
    public static final int COLOR_TEXT = 0xFFE5E7EB;
    public static final int COLOR_MUTED = 0xFF94A3B8;
    public static final int COLOR_CYAN = 0xFF22D3EE;
    static final int COLOR_RED = 0xFFF43F5E;
    static final int COLOR_AMBER = 0xFFF59E0B;
    private static final int COLOR_PANEL_FOCUS = 0xFF26344A;
    private static final int MENU_ITEM_HEIGHT_DP = 48;
    private static final int MENU_MAX_WIDTH_DP = 360;

    private final Context mContext;

    DesktopUiFactory(final Context context) {
        mContext = context;
    }

    public int dp(final int value) {
        return Math.round(value
                * mContext.getResources().getDisplayMetrics().density);
    }

    int desktopDp(
            final int normalValue,
            final int compactValue,
            final boolean compact) {
        return dp(compact ? compactValue : normalValue);
    }

    public TextView sectionTitle(final int titleResId) {
        final TextView title = new TextView(mContext);
        title.setText(titleResId);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    public Button actionButton(final int textResId, final int accentColor) {
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
                new int[] {android.R.attr.state_pressed},
                rounded(COLOR_PANEL_FOCUS, dp(10), accentColor));
        background.addState(
                new int[] {android.R.attr.state_focused},
                rounded(COLOR_PANEL_FOCUS, dp(10), accentColor));
        background.addState(
                new int[0],
                rounded(COLOR_PANEL_ALT, dp(10), accentColor));
        button.setBackground(background);
        return button;
    }

    Button menuItem(final int textResId, final int emphasisColor) {
        return menuItem(mContext.getString(textResId), emphasisColor);
    }

    Button menuItem(final String text, final int emphasisColor) {
        final Button button = new Button(mContext);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        final int enabledColor = emphasisColor == COLOR_RED
                || emphasisColor == COLOR_AMBER
                ? emphasisColor : COLOR_TEXT;
        button.setTextColor(new ColorStateList(
                new int[][] {
                    new int[] {-android.R.attr.state_enabled},
                    new int[0]
                },
                new int[] {COLOR_MUTED, enabledColor}));
        button.setBackground(menuItemBackground());
        return button;
    }

    ImageButton menuIconButton(
            final int drawableResId,
            final int descriptionResId) {
        final ImageButton button = new ImageButton(mContext);
        button.setImageResource(drawableResId);
        button.setColorFilter(COLOR_TEXT);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(menuItemBackground());
        button.setContentDescription(mContext.getString(descriptionResId));
        button.setTooltipText(mContext.getString(descriptionResId));
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        return button;
    }

    TextView menuHeader(
            final CharSequence text,
            final TextUtils.TruncateAt ellipsize) {
        final TextView title = new TextView(mContext);
        title.setText(text);
        title.setTextColor(COLOR_MUTED);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setEllipsize(ellipsize);
        if (ellipsize == TextUtils.TruncateAt.START) {
            title.setSingleLine(true);
        } else {
            title.setMaxLines(2);
        }
        title.setPadding(dp(10), dp(4), dp(10), dp(4));
        return title;
    }

    GradientDrawable menuSurface() {
        return rounded(COLOR_PANEL, dp(8), COLOR_PANEL_FOCUS);
    }

    int menuItemHeight() {
        return dp(MENU_ITEM_HEIGHT_DP);
    }

    int menuWidth(final int availableWidth, final int horizontalMargin) {
        final int boundedWidth = Math.max(1, availableWidth - horizontalMargin * 2);
        return Math.min(dp(MENU_MAX_WIDTH_DP), boundedWidth);
    }

    private StateListDrawable menuItemBackground() {
        final StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[] {-android.R.attr.state_enabled},
                filled(Color.TRANSPARENT, dp(6)));
        background.addState(
                new int[] {android.R.attr.state_pressed},
                filled(COLOR_PANEL_FOCUS, dp(6)));
        background.addState(
                new int[] {android.R.attr.state_focused},
                filled(COLOR_PANEL_FOCUS, dp(6)));
        background.addState(
                new int[] {android.R.attr.state_hovered},
                filled(COLOR_PANEL_ALT, dp(6)));
        background.addState(
                new int[0],
                filled(Color.TRANSPARENT, dp(6)));
        return background;
    }

    private static GradientDrawable filled(
            final int color,
            final int radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
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

    StateListDrawable interactiveRounded(
            final int color,
            final int radius,
            final int accentColor) {
        final StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[] {android.R.attr.state_pressed},
                rounded(COLOR_PANEL_FOCUS, radius, accentColor));
        background.addState(
                new int[] {android.R.attr.state_focused},
                rounded(COLOR_PANEL_FOCUS, radius, accentColor));
        background.addState(
                new int[0],
                rounded(color, radius, accentColor));
        return background;
    }

    public GradientDrawable rounded(
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
