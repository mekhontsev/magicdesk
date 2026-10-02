package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.WeakHashMap;

/** Live native View styling. Weak registrations retain neither Activities nor detached tools. */
public final class UiAppearance {
    private enum Property { TEXT, HINT, BACKGROUND, BACKGROUND_TINT, IMAGE, COMPOUND, BUTTON, CHECK_MARK, PROGRESS }
    @FunctionalInterface private interface Style { void apply(View view, ShellAppearance theme); }
    private static final WeakHashMap<Binding, Boolean> BINDINGS = new WeakHashMap<>();
    private static final WeakHashMap<Paint, Boolean> PAINTS = new WeakHashMap<>();
    private static final WeakHashMap<UiFeedbackDrawable, Boolean> FEEDBACK = new WeakHashMap<>();
    private static final WeakHashMap<UiSymbolDrawable, Boolean> SYMBOLS = new WeakHashMap<>();
    private static final WeakHashMap<AppearanceTextSpan, Boolean> SPANS = new WeakHashMap<>();
    private UiAppearance() {}

    static void registerSpan(AppearanceTextSpan span) { SPANS.put(span, true); }

    public static int color(UiColor role) { return color(null, role); }
    public static int color(Context context, UiColor role) { return AppearanceStore.resolved(context).theme().palette().color(role); }
    static android.graphics.drawable.Drawable symbol(Context context, int resource, UiColor role) {
        final var drawable = new UiSymbolDrawable(context, resource, role);
        synchronized (SYMBOLS) { SYMBOLS.put(drawable, true); }
        return drawable;
    }
    public static void text(TextView view, UiColor role) {
        binding(view).content = role;
        bind(view, Property.TEXT, (v, t) -> ((TextView) v).setTextColor(t.palette().color(role)));
    }
    public static void textStates(TextView view, UiColor enabled) {
        binding(view).content = enabled;
        bind(view, Property.TEXT, (v, t) -> ((TextView) v).setTextColor(states(t, enabled)));
    }
    public static void hint(TextView view, UiColor role) {
        bind(view, Property.HINT, (v, t) -> ((TextView) v).setHintTextColor(t.palette().color(role)));
    }
    public static void background(View view, UiColor role) {
        bind(view, Property.BACKGROUND, (v, t) -> v.setBackgroundColor(t.palette().color(role)));
    }
    public static void backgroundTint(View view, UiColor role) {
        bind(view, Property.BACKGROUND_TINT, (v, t) -> v.setBackgroundTintList(states(t, role)));
    }
    public static void image(ImageView view, UiColor role) {
        bind(view, Property.IMAGE, (v, t) -> ((ImageView) v).setColorFilter(t.palette().color(role)));
    }
    static void icon(ImageView view, int resource, UiColor role) {
        view.setImageDrawable(symbol(view.getContext(), resource, role));
    }
    public static void imageStates(ImageView view, UiColor role) {
        bind(view, Property.IMAGE, (v, t) -> ((ImageView) v).setImageTintList(states(t, role)));
    }
    public static void compound(TextView view, UiColor role) {
        bind(view, Property.COMPOUND, (v, t) -> ((TextView) v).setCompoundDrawableTintList(states(t, role)));
    }
    public static void button(android.widget.CompoundButton view, UiColor role) {
        bind(view, Property.BUTTON, (v, t) -> {
            final var tint = new ColorStateList(new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
                    new int[] {t.palette().color(role), t.palette().color(UiColor.MUTED)});
            if (v instanceof android.widget.Switch toggle) {
                toggle.setThumbTintList(tint);
                toggle.setTrackTintList(tint.withAlpha(90));
            } else ((android.widget.CompoundButton) v).setButtonTintList(tint);
        });
        if (view instanceof android.widget.Switch) component(view, ShellControls.Role.SWITCH, role);
    }
    public static void progress(android.widget.ProgressBar view, UiColor role) {
        bind(view, Property.PROGRESS, (v, t) -> {
            final var tint = ColorStateList.valueOf(t.palette().color(role));
            ((android.widget.ProgressBar) v).setProgressTintList(tint);
            ((android.widget.ProgressBar) v).setProgressBackgroundTintList(
                    ColorStateList.valueOf(t.palette().color(UiColor.MUTED)).withAlpha(90));
            ((android.widget.ProgressBar) v).setIndeterminateTintList(tint);
            if (v instanceof android.widget.SeekBar seek) seek.setThumbTintList(tint);
        });
    }
    static void dialog(android.app.AlertDialog dialog) {
        final View root = dialog.getWindow().getDecorView();
        final Paint background = (Paint) paint(root.getContext(),
                UiColor.PANEL, 8 * root.getResources().getDisplayMetrics().density, UiColor.HOVER);
        background.backdrop = true;
        background.refresh();
        UiBackdrop.bind(dialog.getWindow(), background);
        dialogContents(root);
    }
    private static void dialogContents(View view) {
        if (view instanceof TextView text && view.getTag(R.id.appearance_binding) == null) textStates(text, UiColor.TEXT);
        if (view instanceof android.widget.CheckedTextView) {
            bind(view, Property.CHECK_MARK, (v, t) -> {
                var tint = new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled},
                            new int[] {android.R.attr.state_checked}, new int[0]},
                            new int[] {t.palette().color(UiColor.MUTED), t.palette().color(UiColor.ACCENT),
                                    t.palette().color(UiColor.TEXT)});
                var choice = (android.widget.CheckedTextView) v;
                choice.setCheckMarkTintList(tint);
                // Platform choice layouts can put the indicator in drawableStart instead of checkMark.
                choice.setCompoundDrawableTintList(tint);
            });
        }
        // Adapter rows are materialized after dialog creation, and again as the list scrolls.
        if (view instanceof android.widget.AbsListView list) {
            list.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { dialogContents(child); }
                @Override public void onChildViewRemoved(View parent, View child) { }
            });
        }
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) dialogContents(group.getChildAt(i));
        }
    }
    private static ColorStateList states(ShellAppearance theme, UiColor role) {
        return new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
                new int[] {theme.palette().color(UiColor.MUTED), theme.palette().color(role)});
    }
    private static Binding binding(View view) {
        Binding binding = (Binding) view.getTag(R.id.appearance_binding);
        if (binding == null) {
            binding = new Binding(view);
            view.setTag(R.id.appearance_binding, binding);
            view.addOnAttachStateChangeListener(binding);
            BINDINGS.put(binding, true);
        }
        return binding;
    }
    public static void component(View view, ShellControls.Role role) { component(view, role, UiColor.ACCENT); }
    static void component(View view, ShellControls.Role role, UiColor accent) {
        Binding binding = binding(view);
        binding.control = new UiControlStyle(role, accent, false);
        binding.refresh();
    }
    static void componentText(TextView view, ShellControls.Role role) {
        Binding binding = binding(view);
        binding.control = new UiControlStyle(role, UiColor.ACCENT, true);
        view.setDuplicateParentStateEnabled(true);
        binding.refresh();
    }
    static void componentPadding(View view, int left, int top, int right, int bottom) {
        Binding binding = binding(view);
        if (binding.control == null) throw new IllegalStateException("Control role required before setting host metrics");
        binding.control.setBaselinePadding(left, top, right, bottom);
        binding.refresh();
    }
    private static void bind(View view, Property property, Style style) {
        Binding binding = binding(view);
        binding.styles.put(property, style);
        if (view instanceof android.widget.CompoundButton button && !binding.styles.containsKey(Property.BUTTON)) {
            button(button, UiColor.ACCENT);
        }
        binding.refresh();
    }
    static void refresh() {
        DesktopTaskDescription.refresh();
        UiMotion.refresh();
        for (var span : new ArrayList<>(SPANS.keySet())) span.refresh();
        for (var control : new ArrayList<>(FEEDBACK.keySet())) control.refresh();
        synchronized (SYMBOLS) {
            for (var drawable : new ArrayList<>(SYMBOLS.keySet())) drawable.refresh();
        }
        for (Paint paint : new ArrayList<>(PAINTS.keySet())) paint.refresh();
        UiBackdrop.refreshAll();
        for (Binding binding : new ArrayList<>(BINDINGS.keySet())) binding.refresh();
    }
    static android.graphics.drawable.StateListDrawable feedback(Context context, int radius) {
        final var drawable = new UiFeedbackDrawable(context, context.getResources().getDisplayMetrics().density, radius);
        FEEDBACK.put(drawable, true);
        return drawable;
    }
    static GradientDrawable paint(Context context, UiColor fill, float radiusPx, UiColor border) {
        final Paint paint = new Paint(context, context.getResources().getDisplayMetrics().density, fill, radiusPx, border);
        PAINTS.put(paint, true);
        paint.refresh();
        return paint;
    }
    static GradientDrawable panelPaint(Context context, String panelId) {
        if (AppearanceStore.current(context).composition().panel(panelId) == null) {
            throw new IllegalArgumentException("Unknown appearance panel: " + panelId);
        }
        final Paint paint = new Paint(context, context.getResources().getDisplayMetrics().density,
                UiColor.PANEL, 0, UiColor.SURFACE);
        paint.panelId = panelId;
        paint.backdrop = true;
        PAINTS.put(paint, true);
        paint.refresh();
        return paint;
    }
    private static final class Binding implements View.OnAttachStateChangeListener {
        final View view;
        final AppearanceScopeSource source;
        final EnumMap<Property, Style> styles = new EnumMap<>(Property.class);
        float baseSize;
        Typeface baseFace;
        boolean captured;
        ShellAppearance.Typography appliedTypography;
        Typeface appliedFont;
        ShellControls.Style appliedControl;
        UiControlStyle control;
        UiColor content = UiColor.TEXT;
        Binding(View view) { this.view = view; source = new AppearanceScopeSource(view.getContext()); }
        public void onViewAttachedToWindow(View view) { refresh(); }
        public void onViewDetachedFromWindow(View view) {}
        void refresh() {
            var appearance = source.resolve();
            final ShellAppearance theme = appearance.theme();
            for (Style style : styles.values()) style.apply(view, theme);
            if (control != null) control.apply(view, theme, content);
            applyTypography(theme, appearance.assets());
            view.invalidate();
        }
        void applyTypography(ShellAppearance theme, ThemeAssets.Prepared assets) {
            if (view instanceof TextView text && view.isAttachedToWindow()) {
                if (!captured) {
                    baseSize = text.getTextSize(); baseFace = text.getTypeface(); captured = true;
                }
                ShellControls.Style style = control == null ? null : theme.controls().style(control.role);
                int weight = style == null || style.textWeight() == null ? (baseFace == null ? 400 : baseFace.getWeight()) : style.textWeight();
                Typeface font = assets.font(weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
                if (theme.typography().equals(appliedTypography) && font == appliedFont && java.util.Objects.equals(style, appliedControl)) return;
                appliedTypography = theme.typography();
                appliedFont = font;
                appliedControl = style;
                final String family = switch (theme.typography().font()) {
                    case SANS -> "sans-serif"; case SERIF -> "serif"; case MONO -> "monospace";
                };
                Typeface face = font != null ? font : theme.typography().font() == ShellAppearance.Font.SANS ? baseFace
                        : Typeface.create(family, baseFace == null ? Typeface.NORMAL : baseFace.getStyle());
                text.setTypeface(style != null && style.textWeight() != null ? Typeface.create(face, weight, baseFace != null && baseFace.isItalic()) : face);
                if (text.getAutoSizeTextType() == TextView.AUTO_SIZE_TEXT_TYPE_NONE) {
                    float size = style == null || style.textSizeSp() == null ? baseSize : TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP, style.textSizeSp(), text.getResources().getDisplayMetrics());
                    text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * theme.typography().scale());
                }
            }
        }
    }
    static final class Paint extends GradientDrawable {
        final float density;
        final UiColor fill;
        final float radius;
        final UiColor border;
        final AppearanceScopeSource source;
        String panelId;
        boolean backdrop;
        Paint(Context context, float density, UiColor fill, float radius, UiColor border) {
            this.density = density; this.fill = fill; this.radius = radius; this.border = border;
            source = new AppearanceScopeSource(context);
        }
        void refresh() {
            final ShellAppearance theme = source.current();
            ShellPanel panel = panelId == null ? null : theme.composition().panel(panelId);
            if (panelId != null && panel == null) {
                setColor(0); setStroke(0, 0); setCornerRadius(0); invalidateSelf();
                return;
            }
            final int color = theme.palette().color(fill);
            setColor(backdrop ? (Math.round(255 * backdropStyle().opacity()) << 24) | (color & 0xffffff) : color);
            setCornerRadius(panel != null ? panel.style().radiusDp() * density : radius * theme.shape().radiusScale());
            setStroke(border == UiColor.TRANSPARENT ? 0 : Math.round(density * theme.shape().borderDp()),
                    theme.palette().color(border));
            invalidateSelf();
        }
        ShellAppearance.Backdrop backdropStyle() {
            final ShellAppearance theme = source.current();
            final ShellPanel panel = panelId == null ? null : theme.composition().panel(panelId);
            return panel != null && panel.style().backdrop() != null ? panel.style().backdrop() : theme.backdrop();
        }
    }
}
