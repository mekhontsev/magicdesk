package io.github.mekhontsev.magicdesk;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, density-independent appearance. It owns no window or workspace state. */
public record ShellAppearance(Palette palette, Typography typography, Shape shape, Backdrop backdrop,
        ShellComposition composition, ShellMotion motion,
        Feedback feedback, ShellResources resources, ShellControls controls) {
    public record Feedback(UiColor normal, UiColor hover, UiColor pressed,
            UiColor selected, UiColor focused, UiColor disabled, UiColor outline) {
        public Feedback {
            Objects.requireNonNull(normal); Objects.requireNonNull(hover); Objects.requireNonNull(pressed);
            Objects.requireNonNull(selected); Objects.requireNonNull(focused); Objects.requireNonNull(disabled);
            Objects.requireNonNull(outline);
        }
        public static Feedback defaults() {
            return new Feedback(UiColor.TRANSPARENT, UiColor.SURFACE, UiColor.HOVER,
                    UiColor.SURFACE, UiColor.HOVER, UiColor.TRANSPARENT, UiColor.ACCENT);
        }
    }
    public enum ColorSource { FIXED, SYSTEM }
    public enum ColorMode { LIGHT, DARK, SYSTEM }
    public static final class Palette {
        private final ColorSource source;
        private final ColorMode mode;
        private final String preset;
        private final Map<UiColor, Integer> overrides, colors;
        public Palette(ColorSource source, ColorMode mode, String preset, Map<UiColor, Integer> overrides) {
            this(source, mode, preset, overrides, presetColors(preset));
        }
        private Palette(ColorSource source, ColorMode mode, String preset,
                Map<UiColor, Integer> overrides, Map<UiColor, Integer> base) {
            this.source = Objects.requireNonNull(source); this.mode = Objects.requireNonNull(mode);
            this.preset = Objects.requireNonNull(preset); this.overrides = Map.copyOf(overrides);
            var merged = new EnumMap<UiColor, Integer>(UiColor.class);
            merged.putAll(base); merged.putAll(overrides);
            for (UiColor role : UiColor.values()) Objects.requireNonNull(merged.get(role), role.name());
            if (merged.get(UiColor.TRANSPARENT) != 0) throw new IllegalArgumentException("transparent");
            colors = Map.copyOf(merged);
        }
        public ColorSource source() { return source; }
        public ColorMode mode() { return mode; }
        public String preset() { return preset; }
        public Map<UiColor, Integer> overrides() { return overrides; }
        public Map<UiColor, Integer> colors() { return colors; }
        public int color(UiColor role) { return colors.get(role); }
        public Palette resolve(Map<UiColor, Integer> system) {
            return source == ColorSource.SYSTEM ? new Palette(source, mode, preset, overrides, system) : this;
        }
        public Palette withColor(UiColor role, int color) {
            var values = new EnumMap<UiColor, Integer>(UiColor.class);
            values.putAll(overrides); values.put(role, color);
            return new Palette(source, mode, preset, values);
        }
        public Palette withSource(ColorSource value, ColorMode tone) {
            return new Palette(value, tone, preset, overrides);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Palette p && source == p.source && mode == p.mode && preset.equals(p.preset)
                    && overrides.equals(p.overrides) && colors.equals(p.colors);
        }
        @Override public int hashCode() { return Objects.hash(source, mode, preset, overrides, colors); }
    }
    public enum Font { SANS, SERIF, MONO }
    public record Typography(Font font, float scale) {
        public Typography { Objects.requireNonNull(font); range(scale, .8f, 1.3f, "text scale"); }
    }
    public record Shape(float radiusScale, float borderDp) {
        public Shape { range(radiusScale, 0, 2, "radius scale"); range(borderDp, 0, 3, "border"); }
    }
    public record Backdrop(float opacity, int blurRadiusDp) {
        public Backdrop {
            range(opacity, .15f, 1, "opacity");
            range(blurRadiusDp, 0, 64, "blur radius");
        }
        public static Backdrop defaults() { return new Backdrop(1, 0); }
    }
    public enum Width { FILL, CONTENT }
    public enum Alignment { START, CENTER, END }
    /** A null backdrop inherits the appearance's global backdrop. */
    public record PanelStyle(Width length, Alignment alignment, int maxLengthDp, int sideGapDp,
            int edgeGapDp, int thicknessDp, int paddingDp, int radiusDp, Backdrop backdrop, boolean reserveSpace,
            ShellDockEffect hover) {
        public PanelStyle {
            java.util.Objects.requireNonNull(hover);
            Objects.requireNonNull(length); Objects.requireNonNull(alignment);
            range(maxLengthDp, 64, 4096, "maximum length");
            range(sideGapDp, 0, 96, "side gap"); range(edgeGapDp, 0, 96, "edge gap");
            if (thicknessDp != 0) range(thicknessDp, 40, 160, "panel thickness");
            range(paddingDp, 0, 16, "padding"); range(radiusDp, 0, 32, "radius");
        }
        public static PanelStyle defaults() {
            return new PanelStyle(Width.FILL, Alignment.CENTER, 4096, 0, 0, 0, 8, 0, null, true, ShellDockEffect.NONE);
        }
        public static PanelStyle floating() {
            return new PanelStyle(Width.CONTENT, Alignment.CENTER, 1100, 12, 12, 0, 8, 8, new Backdrop(.88f, 0), true, ShellDockEffect.NONE);
        }
    }
    public ShellAppearance {
        Objects.requireNonNull(palette); Objects.requireNonNull(typography);
        Objects.requireNonNull(shape); Objects.requireNonNull(backdrop);
        Objects.requireNonNull(composition); Objects.requireNonNull(motion);
        Objects.requireNonNull(feedback); Objects.requireNonNull(resources); Objects.requireNonNull(controls);
    }
    public Backdrop panelBackdrop(String id) {
        var panel = composition.panel(id);
        return panel == null || panel.style().backdrop() == null ? backdrop : panel.style().backdrop();
    }
    public ShellAppearance withComposition(ShellComposition value) {
        return new ShellAppearance(palette, typography, shape, backdrop, value, motion, feedback, resources, controls);
    }
    public ShellAppearance withStyle(ShellAppearance value) {
        return new ShellAppearance(value.palette, value.typography, value.shape, backdrop,
                composition, motion, value.feedback, resources, value.controls);
    }
    public ShellAppearance withPalette(Palette value) {
        return new ShellAppearance(value, typography, shape, backdrop, composition, motion, feedback, resources, controls);
    }
    public ShellAppearance withTypography(Typography value) {
        return new ShellAppearance(palette, value, shape, backdrop, composition, motion, feedback, resources, controls);
    }
    public ShellAppearance withShape(Shape value) {
        return new ShellAppearance(palette, typography, value, backdrop, composition, motion, feedback, resources, controls);
    }
    public ShellAppearance withBackdrop(Backdrop value) {
        return new ShellAppearance(palette, typography, shape, value, composition, motion, feedback, resources, controls);
    }
    public ShellAppearance withResources(ShellResources value) {
        return new ShellAppearance(palette, typography, shape, backdrop, composition, motion, feedback, value, controls);
    }
    public ShellAppearance withControls(ShellControls value) {
        return new ShellAppearance(palette, typography, shape, backdrop, composition, motion, feedback, resources, value);
    }
    public static ShellAppearance defaults() { return preset("dark"); }
    public static ShellAppearance preset(String name) {
        return new ShellAppearance(new Palette(ColorSource.FIXED, ColorMode.SYSTEM, name, Map.of()), new Typography(Font.SANS, 1),
                new Shape(1, 1), Backdrop.defaults(), ShellComposition.defaults(), ShellMotion.defaults(),
                Feedback.defaults(), ShellResources.defaults(), ShellControls.defaults());
    }
    private static Map<UiColor, Integer> presetColors(String name) {
        final int[] values = switch (name) {
            case "dark" -> new int[] {0xff090d14, 0xff111827, 0xff172033, 0xffe5e7eb,
                    0xff94a3b8, 0xff22d3ee, 0xfff43f5e, 0xfff59e0b, 0xff26344a, 0xffe5e7eb, 0};
            case "light" -> new int[] {0xffeef1f4, 0xfffafbfc, 0xffe2e7eb, 0xff18222b,
                    0xff52616f, 0xff007a83, 0xffb51c36, 0xff925800, 0xffccd8df, 0xffe5e7eb, 0};
            case "contrast" -> new int[] {0xff000000, 0xff000000, 0xff202020, 0xffffffff,
                    0xffcccccc, 0xff00ffff, 0xffff8080, 0xffffff00, 0xff454545, 0xffffffff, 0};
            default -> throw new IllegalArgumentException("Unknown appearance preset: " + name);
        };
        final EnumMap<UiColor, Integer> colors = new EnumMap<>(UiColor.class);
        UiColor[] roles = {UiColor.BACKGROUND, UiColor.PANEL, UiColor.SURFACE, UiColor.TEXT, UiColor.MUTED,
                UiColor.ACCENT, UiColor.DANGER, UiColor.ATTENTION, UiColor.HOVER, UiColor.DESKTOP_TEXT, UiColor.TRANSPARENT};
        for (int i = 0; i < roles.length; i++) colors.put(roles[i], values[i]);
        colors.put(UiColor.SURFACE_LOW, colors.get(UiColor.PANEL));
        colors.put(UiColor.SURFACE_HIGH, colors.get(UiColor.HOVER));
        colors.put(UiColor.ON_ACCENT, name.equals("light") ? 0xffffffff : 0xff002d32);
        colors.put(UiColor.ACCENT_CONTAINER, name.equals("light") ? 0xffb0ecef : 0xff12454c);
        colors.put(UiColor.ON_ACCENT_CONTAINER, name.equals("light") ? 0xff002d32 : 0xffb0ecef);
        colors.put(UiColor.OUTLINE, colors.get(UiColor.MUTED));
        return colors;
    }
    static void range(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }
}
