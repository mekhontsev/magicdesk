package io.github.mekhontsev.magicdesk;

import java.util.Map;
import java.util.Objects;

/** Semantic control presentation, independent of View classes and shell composition. */
public record ShellControls(Map<Role, Style> styles) {
    static final float DISABLED_OPACITY = .38f;

    static int disabledContent(ShellAppearance.Palette palette) {
        int color = palette.color(UiColor.MUTED);
        return (Math.round((color >>> 24) * DISABLED_OPACITY) << 24) | (color & 0xffffff);
    }

    public enum Role { ACTION_BUTTON, PANEL_BUTTON, SEARCH_FIELD, TAB, SWITCH, SETTINGS_ROW, APP_TILE }
    public enum State { HOVER, PRESSED, SELECTED, FOCUSED, DISABLED }
    public enum Shape { ROUNDED, CAPSULE }

    public record Paint(UiColor fill, UiColor content, UiColor outline,
            UiColor layer, float layerOpacity, float opacity) {
        public Paint {
            Objects.requireNonNull(fill); Objects.requireNonNull(content);
            Objects.requireNonNull(outline); Objects.requireNonNull(layer);
            ShellAppearance.range(layerOpacity, 0, 1, "state layer opacity");
            ShellAppearance.range(opacity, 0, 1, "control opacity");
        }
        public int background(ShellAppearance.Palette palette) {
            int base = palette.color(fill), overlay = palette.color(layer);
            float a = (overlay >>> 24) / 255f * layerOpacity;
            float b = (base >>> 24) / 255f * (1 - a), alpha = a + b;
            if (alpha == 0) return 0;
            int result = Math.round(alpha * opacity * 255) << 24;
            for (int shift = 16; shift >= 0; shift -= 8) {
                result |= Math.round((((overlay >>> shift) & 255) * a
                        + ((base >>> shift) & 255) * b) / alpha) << shift;
            }
            return result;
        }
        public int contentColor(ShellAppearance.Palette palette) {
            return withOpacity(palette.color(content));
        }
        public int outlineColor(ShellAppearance.Palette palette) {
            return withOpacity(palette.color(outline));
        }
        private int withOpacity(int color) {
            return (Math.round((color >>> 24) * opacity) << 24) | (color & 0xffffff);
        }
    }

    /** Null metrics preserve the host's density-aware baseline, including its touch target. */
    public record Style(Shape shape, Float radiusDp, Float borderDp,
            Integer paddingHorizontalDp, Integer paddingVerticalDp, Integer minHeightDp,
            Float textSizeSp, Integer textWeight, Paint normal, Map<State, Paint> states) {
        public Style {
            check(radiusDp, 0, 48, "control radius"); check(borderDp, 0, 4, "control border");
            check(paddingHorizontalDp, 0, 32, "horizontal padding");
            check(paddingVerticalDp, 0, 24, "vertical padding");
            check(minHeightDp, 0, 96, "minimum height");
            check(textSizeSp, 8, 32, "control text size"); check(textWeight, 100, 900, "text weight");
            states = Map.copyOf(states);
        }
        public static Style inherit() {
            return new Style(null, null, null, null, null, null, null, null, null, Map.of());
        }
        private static void check(Number value, float min, float max, String name) {
            if (value != null) ShellAppearance.range(value.floatValue(), min, max, name);
        }
    }
    public ShellControls { styles = Map.copyOf(styles); }
    public static ShellControls defaults() { return new ShellControls(Map.of()); }
    private static final Style INHERIT = Style.inherit();
    public Style style(Role role) { return styles.getOrDefault(role, INHERIT); }
}
