package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.UiColor.*;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class DesktopUiFactory {
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
        UiAppearance.text(title, TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    android.widget.Spinner spinner(final String[] items) {
        final android.widget.Spinner spinner = new android.widget.Spinner(mContext);
        UiAppearance.backgroundTint(spinner, MUTED);
        spinner.setAdapter(new android.widget.ArrayAdapter<String>(mContext, android.R.layout.simple_spinner_dropdown_item, items) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                final View view = super.getView(position, convert, parent);
                UiAppearance.text((TextView) view, TEXT);
                return view;
            }
            @Override public View getDropDownView(int position, View convert, android.view.ViewGroup parent) {
                final View view = super.getDropDownView(position, convert, parent);
                UiAppearance.text((TextView) view, TEXT);
                UiAppearance.background(view, PANEL);
                return view;
            }
        });
        return spinner;
    }

    public void addControlSection(
            final LinearLayout parent, final int titleResId, final int spacing) {
        if (parent.getChildCount() > 0) {
            final View divider = new View(mContext);
            UiAppearance.background(divider, HOVER);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            params.setMargins(0, spacing, 0, spacing);
            parent.addView(divider, params);
        }
        final TextView title = sectionTitle(titleResId);
        title.setTextSize(14);
        title.setAccessibilityHeading(true);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        parent.addView(title, params);
    }

    public Button actionButton(final int textResId, final UiColor accentColor) {
        return actionButton(mContext.getString(textResId), accentColor);
    }

    Button actionButton(final String text, final UiColor accentColor) {
        final Button button = new Button(mContext);
        button.setText(text);
        button.setAllCaps(false);
        UiAppearance.textStates(button, TEXT);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        UiAppearance.component(button, ShellControls.Role.ACTION_BUTTON, accentColor);
        return button;
    }

    Button menuItem(final int textResId, final UiColor emphasisColor) {
        return menuItem(mContext.getString(textResId), emphasisColor);
    }

    Button menuItem(final String text, final UiColor emphasisColor) {
        final Button button = new Button(mContext);
        styleMenuItem(button, text, emphasisColor);
        return button;
    }

    android.widget.CheckBox menuCheckBox(final String text, final boolean checked) {
        final android.widget.CheckBox button = new android.widget.CheckBox(mContext);
        styleMenuItem(button, text, TEXT);
        UiAppearance.button(button, TEXT);
        button.setChecked(checked);
        return button;
    }

    private void styleMenuItem(final Button button, final String text, final UiColor emphasisColor) {
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
        final UiColor enabledColor = emphasisColor == DANGER
                || emphasisColor == ATTENTION
                ? emphasisColor : TEXT;
        UiAppearance.textStates(button, enabledColor);
        button.setBackground(menuItemBackground());
    }

    ImageButton menuIconButton(
            final int drawableResId,
            final int descriptionResId) {
        final ImageButton button = new ImageButton(mContext);
        button.setImageResource(drawableResId);
        UiAppearance.imageStates(button, TEXT);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(menuItemBackground());
        button.setContentDescription(mContext.getString(descriptionResId));
        button.setTooltipText(mContext.getString(descriptionResId));
        button.setStateListAnimator(null);
        button.setDefaultFocusHighlightEnabled(false);
        return button;
    }

    Button controlAction(final int textResId, final int iconResId, final UiColor emphasisColor) {
        final Button button = menuItem(textResId, emphasisColor);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setTextSize(14);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        UiAppearance.compound(button, emphasisColor == DANGER ? DANGER : TEXT);
        setControlIcon(button, iconResId);
        button.setCompoundDrawablePadding(dp(10));
        return button;
    }

    void setControlIcon(final Button button, final int iconResId) {
        final Drawable icon = mContext.getDrawable(iconResId).mutate();
        icon.setBounds(0, 0, dp(22), dp(22));
        button.setCompoundDrawablesRelative(icon, null, null, null);
    }

    TextView menuHeader(
            final CharSequence text,
            final TextUtils.TruncateAt ellipsize) {
        final TextView title = new TextView(mContext);
        title.setText(text);
        UiAppearance.text(title, MUTED);
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
        return rounded(PANEL, dp(8), HOVER);
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
                filled(TRANSPARENT, dp(6)));
        background.addState(
                new int[] {android.R.attr.state_pressed},
                filled(HOVER, dp(6)));
        background.addState(
                new int[] {android.R.attr.state_focused},
                filled(HOVER, dp(6)));
        background.addState(
                new int[] {android.R.attr.state_hovered},
                filled(SURFACE, dp(6)));
        background.addState(
                new int[0],
                filled(TRANSPARENT, dp(6)));
        return background;
    }

    private GradientDrawable filled(
            final UiColor color,
            final int radius) {
        return UiAppearance.paint(mContext, color, radius, TRANSPARENT);
    }

    Button smallButton(final int textResId, final UiColor accentColor) {
        return smallButton(mContext.getString(textResId), accentColor);
    }

    Button smallButton(final String text, final UiColor accentColor) {
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
        UiAppearance.icon(button, drawableResId, TEXT);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        UiAppearance.component(button, ShellControls.Role.PANEL_BUTTON);
        button.setContentDescription(mContext.getString(descriptionResId));
        button.setTooltipText(mContext.getString(descriptionResId));
        return button;
    }

    StateListDrawable interactiveRounded(
            final UiColor color,
            final int radius,
            final UiColor accentColor) {
        final StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[] {android.R.attr.state_pressed},
                rounded(HOVER, radius, accentColor));
        background.addState(
                new int[] {android.R.attr.state_focused},
                rounded(HOVER, radius, accentColor));
        background.addState(
                new int[0],
                rounded(color, radius, accentColor));
        return background;
    }

    public GradientDrawable rounded(
            final UiColor color,
            final int radius,
            final UiColor strokeColor) {
        return UiAppearance.paint(mContext, color, radius, strokeColor);
    }

    StateListDrawable flatButtonBackground(final int radius) {
        return UiAppearance.feedback(mContext, radius);
    }
}
