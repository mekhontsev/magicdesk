package io.github.mekhontsev.magicdesk;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import static io.github.mekhontsev.magicdesk.UiColor.*;

/** Retained state drawables and baseline metrics; a theme edit never replaces the control. */
final class UiControlStyle {
    private static final int[][] STATES = {{-android.R.attr.state_enabled}, {android.R.attr.state_pressed},
            {android.R.attr.state_focused}, {android.R.attr.state_checked}, {android.R.attr.state_selected},
            {android.R.attr.state_hovered}, {}};
    private static final ShellControls.State[] ROLES = {ShellControls.State.DISABLED, ShellControls.State.PRESSED,
            ShellControls.State.FOCUSED, ShellControls.State.SELECTED, ShellControls.State.SELECTED, ShellControls.State.HOVER, null};
    final ShellControls.Role role;
    private final UiColor accent;
    private final boolean textOnly;
    private final StateListDrawable background;
    private final GradientDrawable[] paints;
    private boolean captured;
    private int left, top, right, bottom, minHeight, textMinHeight;

    UiControlStyle(ShellControls.Role role, UiColor accent, boolean textOnly) {
        this.role = role; this.accent = accent; this.textOnly = textOnly;
        background = textOnly || role == ShellControls.Role.SWITCH ? null : new StateListDrawable();
        paints = background == null ? null : new GradientDrawable[STATES.length];
        if (paints != null) for (int i = 0; i < paints.length; i++) {
            paints[i] = new GradientDrawable(); background.addState(STATES[i], paints[i]);
        }
    }
    void apply(View view, ShellAppearance theme, UiColor contentRole) {
        var style = theme.controls().style(role);
        float density = view.getResources().getDisplayMetrics().density;
        int[] content = new int[STATES.length], fills = new int[STATES.length];
        float radius = style.shape() == ShellControls.Shape.CAPSULE ? 10000 : density * theme.shape().radiusScale()
                * (style.radiusDp() == null ? defaultRadius() : style.radiusDp());
        int border = Math.round(density * (style.borderDp() == null ? theme.shape().borderDp() : style.borderDp()));
        for (int i = 0; i < STATES.length; i++) {
            var paint = ROLES[i] == null ? style.normal() : style.states().get(ROLES[i]);
            if (paint == null) paint = defaultPaint(theme, style.normal(), ROLES[i], contentRole);
            content[i] = paint.contentColor(theme.palette()); fills[i] = paint.background(theme.palette());
            if (paints != null) {
                paints[i].setColor(fills[i]); paints[i].setCornerRadius(radius);
                paints[i].setStroke(paint.outline() == TRANSPARENT ? 0 : border, paint.outlineColor(theme.palette()));
            }
        }
        var colors = new ColorStateList(STATES, content);
        if (role == ShellControls.Role.SWITCH && view instanceof android.widget.Switch toggle) {
            toggle.setThumbTintList(colors); toggle.setTrackTintList(new ColorStateList(STATES, fills));
        } else {
            if (!textOnly && view.getBackground() != background) view.setBackground(background);
            if (view instanceof TextView text) {
                text.setTextColor(colors); text.setCompoundDrawableTintList(colors);
            } else if (view instanceof ImageView image) image.setImageTintList(colors);
        }
        int duration = theme.motion().reduced() ? 0 : theme.motion().feedbackMs();
        if (background != null) { background.setEnterFadeDuration(duration); background.setExitFadeDuration(duration); }
        if (!textOnly && view.isAttachedToWindow()) {
            if (!captured) {
                left = view.getPaddingLeft(); top = view.getPaddingTop(); right = view.getPaddingRight(); bottom = view.getPaddingBottom();
                minHeight = view.getMinimumHeight(); textMinHeight = view instanceof TextView text ? text.getMinHeight() : -1;
                captured = true;
            }
            Integer h = style.paddingHorizontalDp(), v = style.paddingVerticalDp(), height = style.minHeightDp();
            int l = h == null ? left : Math.round(density * h), r = h == null ? right : Math.round(density * h);
            int t = v == null ? top : Math.round(density * v), b = v == null ? bottom : Math.round(density * v);
            if (l != view.getPaddingLeft() || t != view.getPaddingTop() || r != view.getPaddingRight() || b != view.getPaddingBottom()) view.setPadding(l, t, r, b);
            int minimum = height == null ? minHeight : Math.round(density * height);
            if (view.getMinimumHeight() != minimum) view.setMinimumHeight(minimum);
            if (view instanceof TextView text) {
                int textMinimum = height == null ? textMinHeight : minimum;
                if (textMinimum >= 0 && text.getMinHeight() != textMinimum) text.setMinHeight(textMinimum);
            }
        }
    }
    private int defaultRadius() {
        return switch (role) { case ACTION_BUTTON -> 10; case SETTINGS_ROW -> 4; case APP_TILE -> 12; default -> 8; };
    }
    private ShellControls.Paint defaultPaint(ShellAppearance theme, ShellControls.Paint normal, ShellControls.State state, UiColor contentRole) {
        if (role == ShellControls.Role.SWITCH) {
            boolean selected = state == ShellControls.State.SELECTED;
            return new ShellControls.Paint(selected ? ACCENT_CONTAINER : SURFACE_HIGH, selected ? accent : MUTED,
                    TRANSPARENT, TRANSPARENT, 0, state == ShellControls.State.DISABLED ? .38f : 1);
        }
        if (normal != null) {
            if (state == null) return normal;
            float overlay = switch (state) {
                case HOVER -> .08f; case PRESSED, FOCUSED -> .12f; case SELECTED -> .16f; default -> 0;
            };
            return new ShellControls.Paint(normal.fill(), normal.content(), state == ShellControls.State.FOCUSED ? theme.feedback().outline() : normal.outline(),
                    normal.content(), overlay, state == ShellControls.State.DISABLED ? normal.opacity() * .38f : normal.opacity());
        }
        UiColor fill = role == ShellControls.Role.ACTION_BUTTON || role == ShellControls.Role.SEARCH_FIELD ? SURFACE : theme.feedback().normal();
        UiColor outline = role == ShellControls.Role.ACTION_BUTTON ? accent : TRANSPARENT;
        UiColor content = contentRole;
        if (state != null) switch (state) {
            case DISABLED -> { content = MUTED; fill = theme.feedback().disabled(); outline = TRANSPARENT; }
            case HOVER -> fill = theme.feedback().hover();
            case PRESSED -> fill = theme.feedback().pressed();
            case SELECTED -> { fill = theme.feedback().selected(); outline = theme.feedback().outline(); }
            case FOCUSED -> { fill = theme.feedback().focused(); outline = theme.feedback().outline(); }
        }
        return new ShellControls.Paint(fill, content, outline, TRANSPARENT, 0, 1);
    }
}
