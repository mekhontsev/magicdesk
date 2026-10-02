package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import static io.github.mekhontsev.magicdesk.ShellAppearanceSchema.name;
import static io.github.mekhontsev.magicdesk.ShellAppearanceSchema.invalid;

/** Validate once at the boundary; renderers consume immutable typed values. */
final class ShellAppearanceJson {
    static final int MAX_BYTES = 32768;
    static ShellAppearance parse(String text) throws JSONException {
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("", "document exceeds 32 KiB");
        final JSONTokener tokener = new JSONTokener(text) {
            private int depth;
            @Override public Object nextValue() throws JSONException {
                if (++depth > 12) throw new JSONException("Appearance nesting is too deep");
                try { return super.nextValue(); } finally { depth--; }
            }
        };
        final JSONObject root = new JSONObject(tokener);
        if (tokener.nextClean() != 0) throw invalid("", "trailing JSON content");
        ShellAppearanceSchema.validate(root);
        final EnumMap<UiColor, Integer> colors = new EnumMap<>(UiColor.class);
        final JSONObject palette = object(root, "colors");
        for (var it = palette.keys(); it.hasNext();) {
            String key = it.next();
            if (palette.isNull(key)) continue;
            colors.put(value(UiColor.class, key), (int) (0xff000000L | Long.parseLong(palette.getString(key).substring(1), 16)));
        }
        final JSONObject type = object(root, "typography"), shape = object(root, "shape");
        final JSONObject motion = object(root, "motion"), feedback = object(root, "feedback");
        final EnumMap<ShellResources.Icon, ShellResources.Icon> icons = new EnumMap<>(ShellResources.Icon.class);
        final JSONObject resources = object(object(root, "resources"), "icons");
        for (var it = resources.keys(); it.hasNext();) {
            String key = it.next();
            icons.put(value(ShellResources.Icon.class, key), value(ShellResources.Icon.class, resources.getString(key)));
        }
        final JSONObject resourceRoot = object(root, "resources");
        final JSONObject requestedAssets = object(resourceRoot, "iconAssets");
        final EnumMap<ShellResources.Icon, String> iconAssets = new EnumMap<>(ShellResources.Icon.class);
        for (var keys = requestedAssets.keys(); keys.hasNext();) {
            String key = keys.next(); iconAssets.put(value(ShellResources.Icon.class, key), requestedAssets.getString(key));
        }
        final JSONObject colorSource = object(root, "palette");
        return new ShellAppearance(new ShellAppearance.Palette(
                value(ShellAppearance.ColorSource.class, colorSource.optString("source", "fixed")),
                value(ShellAppearance.ColorMode.class, colorSource.optString("mode", "system")),
                colorSource.optString("preset", root.optString("preset", "dark")), colors),
                new ShellAppearance.Typography(value(ShellAppearance.Font.class, type.optString("font", "sans")), number(type, "scale", 1)),
                new ShellAppearance.Shape(number(shape, "radiusScale", 1), number(shape, "borderDp", 1)),
                backdrop(object(root, "backdrop")),
                composition(object(root, "composition")),
                new ShellMotion(motion.optBoolean("reduced", false), value(ShellMotion.Effect.class, motion.optString("panels", "none")),
                        value(ShellMotion.Effect.class, motion.optString("taskbar", "none")), motion.optInt("durationMs", 160),
                        motion.optInt("feedbackMs", 0), value(ShellMotion.Curve.class, motion.optString("curve", "ease_out")),
                        motion.optInt("distanceDp", 12), number(motion, "scaleFrom", .96f), motion.optBoolean("wallpaper", true)),
                new ShellAppearance.Feedback(role(feedback, "normal", UiColor.TRANSPARENT), role(feedback, "hover", UiColor.SURFACE),
                        role(feedback, "pressed", UiColor.HOVER), role(feedback, "selected", UiColor.SURFACE),
                        role(feedback, "focused", UiColor.HOVER), role(feedback, "disabled", UiColor.TRANSPARENT),
                        role(feedback, "outline", UiColor.ACCENT)), new ShellResources(icons,
                                resourceRoot.optString("bundle", ""), iconAssets, resourceRoot.optString("font", ""), resourceRoot.optString("wallpaper", ""),
                                ShaderWallpaperJson.parse(resourceRoot.optJSONObject("shader"))),
                ShellControlsJson.parse(root.optJSONObject("controls")));
    }

    private static ShellComposition composition(JSONObject input) throws JSONException {
        var defaults = ShellComposition.defaults();
        var panels = new ArrayList<ShellPanel>();
        var seen = new HashSet<ShellComposition.Kind>();
        var ids = new HashSet<String>();
        JSONArray requestedPanels = input.optJSONArray("panels");
        if (requestedPanels == null) panels.addAll(defaults.panels());
        else for (int p = 0; p < requestedPanels.length(); p++) {
            JSONObject panel = requestedPanels.getJSONObject(p);
            String panelPath = "/composition/panels/" + p;
            String id = panel.getString("id");
            if (!ids.add(id)) throw invalid(panelPath + "/id", "panel already present");
            var components = new ArrayList<ShellComposition.Component>();
            JSONArray bar = panel.getJSONArray("components");
            for (int i = 0; i < bar.length(); i++) {
            JSONObject item = bar.getJSONObject(i);
            var kind = value(ShellComposition.Kind.class, item.getString("type"));
            String path = panelPath + "/components/" + i;
            if (kind != ShellComposition.Kind.SPACER && !seen.add(kind)) throw invalid(path + "/type", "component already present");
            if (item.has("label") && kind != ShellComposition.Kind.START) throw invalid(path + "/label", "only valid for start");
            if (item.has("clock") && kind != ShellComposition.Kind.CLOCK) throw invalid(path + "/clock", "only valid for clock");
            if (item.has("battery") && kind != ShellComposition.Kind.BATTERY) throw invalid(path + "/battery", "only valid for battery");
            if (item.has("indicator") && kind != ShellComposition.Kind.TASKS) throw invalid(path + "/indicator", "only valid for tasks");
            components.add(new ShellComposition.Component(kind, item.optInt("widthDp", 0), item.optInt("minViewportDp", 0),
                    value(ShellComposition.Visibility.class, item.optString("visibility", name(ShellComposition.Component.of(kind).visibility()))),
                    item.optString("label", ""), value(ShellComposition.Clock.class, item.optString("clock", "time")),
                    value(ShellComposition.Group.class, item.optString("group", "start")),
                    value(ShellComposition.Battery.class, item.optString("battery", "percent")),
                    value(ShellComposition.Indicator.class, item.optString("indicator", "line"))));
            }
            JSONObject barStyle = object(panel, "style");
            var b = ShellAppearance.PanelStyle.defaults();
            panels.add(new ShellPanel(id, value(ShellPanel.Edge.class, panel.optString("edge", "bottom")),
                    new ShellAppearance.PanelStyle(value(ShellAppearance.Width.class, barStyle.optString("length", "fill")),
                            value(ShellAppearance.Alignment.class, barStyle.optString("alignment", "center")),
                            barStyle.optInt("maxLengthDp", b.maxLengthDp()), barStyle.optInt("sideGapDp", b.sideGapDp()),
                            barStyle.optInt("edgeGapDp", b.edgeGapDp()), barStyle.optInt("thicknessDp", b.thicknessDp()),
                            barStyle.optInt("paddingDp", b.paddingDp()), barStyle.optInt("radiusDp", b.radiusDp()),
                            barStyle.has("backdrop") ? backdrop(barStyle.getJSONObject("backdrop")) : null,
                            barStyle.optBoolean("reserveSpace", b.reserveSpace())), components));
        }
        JSONObject start = object(input, "start");
        var sections = new ArrayList<ShellComposition.Section>();
        JSONArray requested = start.optJSONArray("sections");
        if (requested == null) sections.addAll(defaults.start().sections());
        else for (int i = 0; i < requested.length(); i++) sections.add(value(ShellComposition.Section.class, requested.getString(i)));
        if (!sections.contains(ShellComposition.Section.APPS)) throw invalid("/composition/start/sections", "apps is required");
        return new ShellComposition(panels, new ShellComposition.Start(sections,
                value(ShellComposition.Presentation.class, start.optString("presentation", "grid")),
                start.optInt("tileWidthDp", defaults.start().tileWidthDp()), start.optInt("iconSizeDp", 44),
                value(ShellComposition.Navigation.class, start.optString("navigation", "scroll")), start.optInt("gapDp", 4)));
    }

    static JSONObject encode(ShellAppearance value) throws JSONException {
        final JSONObject colors = new JSONObject();
        for (UiColor role : UiColor.values()) if (role != UiColor.TRANSPARENT) {
            Integer color = value.palette().overrides().get(role);
            colors.put(name(role), color == null ? JSONObject.NULL : String.format(Locale.ROOT, "#%06X", color & 0xffffff));
        }
        var m = value.motion(); var f = value.feedback(); var s = value.composition().start();
        JSONArray panels = new JSONArray(), sections = new JSONArray();
        for (var panel : value.composition().panels()) {
        JSONArray components = new JSONArray();
        for (var c : panel.components()) {
            JSONObject item = new JSONObject().put("type", name(c.type())).put("widthDp", c.widthDp())
                    .put("minViewportDp", c.minViewportDp()).put("visibility", name(c.visibility())).put("group", name(c.group()));
            if (c.type() == ShellComposition.Kind.START) item.put("label", c.label());
            if (c.type() == ShellComposition.Kind.CLOCK) item.put("clock", name(c.clock()));
            if (c.type() == ShellComposition.Kind.BATTERY) item.put("battery", name(c.battery()));
            if (c.type() == ShellComposition.Kind.TASKS) item.put("indicator", name(c.indicator()));
            components.put(item);
        }
        var t = panel.style();
        JSONObject style = new JSONObject().put("length", name(t.length())).put("alignment", name(t.alignment()))
                .put("maxLengthDp", t.maxLengthDp()).put("sideGapDp", t.sideGapDp()).put("edgeGapDp", t.edgeGapDp())
                .put("thicknessDp", t.thicknessDp()).put("paddingDp", t.paddingDp()).put("radiusDp", t.radiusDp())
                .put("reserveSpace", t.reserveSpace());
        if (t.backdrop() != null) style.put("backdrop", encodeBackdrop(t.backdrop()));
        panels.put(new JSONObject().put("id", panel.id()).put("edge", name(panel.edge())).put("components", components)
                .put("style", style));
        }
        for (var section : s.sections()) sections.put(name(section));
        JSONObject icons = new JSONObject();
        for (var icon : value.resources().icons().entrySet()) icons.put(name(icon.getKey()), name(icon.getValue()));
        JSONObject assets = new JSONObject();
        for (var icon : value.resources().iconAssets().entrySet()) assets.put(name(icon.getKey()), icon.getValue());
        return new JSONObject().put("version", 5).put("colors", colors)
                .put("palette", new JSONObject().put("source", name(value.palette().source()))
                        .put("mode", name(value.palette().mode())).put("preset", value.palette().preset()))
                .put("controls", ShellControlsJson.encode(value.controls()))
                .put("typography", new JSONObject().put("font", name(value.typography().font())).put("scale", Float.valueOf(value.typography().scale())))
                .put("shape", new JSONObject().put("radiusScale", Float.valueOf(value.shape().radiusScale())).put("borderDp", Float.valueOf(value.shape().borderDp())))
                .put("backdrop", encodeBackdrop(value.backdrop()))
                .put("composition", new JSONObject().put("panels", panels).put("start", new JSONObject()
                        .put("sections", sections).put("presentation", name(s.presentation())).put("tileWidthDp", s.tileWidthDp()).put("iconSizeDp", s.iconSizeDp())
                        .put("navigation", name(s.navigation())).put("gapDp", s.gapDp())))
                .put("motion", new JSONObject().put("reduced", m.reduced()).put("panels", name(m.panels())).put("taskbar", name(m.taskbar()))
                        .put("durationMs", m.durationMs()).put("feedbackMs", m.feedbackMs()).put("curve", name(m.curve()))
                        .put("distanceDp", m.distanceDp()).put("scaleFrom", Float.valueOf(m.scaleFrom())).put("wallpaper", m.wallpaper()))
                .put("feedback", new JSONObject().put("normal", name(f.normal())).put("hover", name(f.hover())).put("pressed", name(f.pressed()))
                        .put("selected", name(f.selected())).put("focused", name(f.focused())).put("disabled", name(f.disabled())).put("outline", name(f.outline())))
                .put("resources", new JSONObject().put("icons", icons).put("bundle", value.resources().bundle())
                        .put("iconAssets", assets).put("font", value.resources().font()).put("wallpaper", value.resources().wallpaper())
                        .put("shader", ShaderWallpaperJson.encode(value.resources().shader())));
    }
    private static ShellAppearance.Backdrop backdrop(JSONObject value) {
        return new ShellAppearance.Backdrop(number(value, "opacity", 1), value.optInt("blurRadiusDp", 0));
    }
    private static JSONObject encodeBackdrop(ShellAppearance.Backdrop value) throws JSONException {
        return new JSONObject().put("opacity", Float.valueOf(value.opacity())).put("blurRadiusDp", value.blurRadiusDp());
    }
    private static <T extends Enum<T>> T value(Class<T> type, String value) { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
    private static JSONObject object(JSONObject root, String key) throws JSONException { return root.has(key) ? root.getJSONObject(key) : new JSONObject(); }
    private static float number(JSONObject value, String key, float fallback) { return (float) value.optDouble(key, fallback); }
    private static UiColor role(JSONObject value, String key, UiColor fallback) { return value(UiColor.class, value.optString(key, name(fallback))); }
}
