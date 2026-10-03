package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/** The published schema and input validator share one vocabulary; no external schemas are executed. */
final class ShellAppearanceSchema {
    private static final JSONObject DOCUMENT = build();

    static JSONObject document() throws JSONException { return new JSONObject(DOCUMENT.toString()); }
    static void validate(JSONObject value) throws JSONException { validate(DOCUMENT, value, ""); }
    static void validatePatch(JSONObject value) throws JSONException { validate(DOCUMENT, value, "", true); }

    private static JSONObject build() {
        try {
            JSONObject colors = new JSONObject();
            for (UiColor role : UiColor.values()) if (role != UiColor.TRANSPARENT) {
                colors.put(name(role), nullable(type("string").put("pattern", "^#[0-9a-fA-F]{6}$")));
            }
            JSONObject feedback = new JSONObject();
            for (String state : new String[] {"normal", "hover", "pressed", "selected", "focused", "disabled", "outline"}) {
                feedback.put(state, enumeration(UiColor.values()));
            }
            JSONObject icons = new JSONObject();
            for (var role : ShellResources.Icon.values()) icons.put(name(role), enumeration(ShellResources.Icon.values()));
            JSONObject iconAssets = new JSONObject();
            for (var role : ShellResources.Icon.values()) iconAssets.put(name(role), assetPath(false));
            JSONObject component = object(new JSONObject()
                    .put("type", enumeration(ShellComposition.Kind.values()))
                    .put("widthDp", new JSONObject().put("oneOf", new JSONArray()
                            .put(type("integer").put("const", 0)).put(number(true, 32, 240))))
                    .put("minViewportDp", number(true, 0, 4096))
                    .put("visibility", enumeration(ShellComposition.Visibility.values()))
                    .put("group", enumeration(ShellComposition.Group.values()))
                    .put("battery", enumeration(ShellComposition.Battery.values()))
                    .put("indicator", enumeration(ShellComposition.Indicator.values()))
                    .put("label", type("string").put("maxLength", 32).put("pattern", "^[^\\u0000-\\u001f\\u007f-\\u009f]*$")
                            .put("description", "Start label; empty shows the Start symbol. Only valid for start."))
                    .put("clock", enumeration(ShellComposition.Clock.values())
                            .put("description", "Clock display format; only valid for clock.")))
                    .put("required", new JSONArray().put("type"));
            JSONObject panel = object(new JSONObject()
                    .put("id", type("string").put("pattern", "^[a-z][a-z0-9_-]{0,31}$"))
                    .put("edge", enumeration(ShellPanel.Edge.values()))
                    .put("style", object(new JSONObject()
                            .put("length", enumeration(ShellAppearance.Width.values())).put("alignment", enumeration(ShellAppearance.Alignment.values()))
                            .put("maxLengthDp", number(true, 64, 4096)).put("sideGapDp", number(true, 0, 96))
                            .put("edgeGapDp", number(true, 0, 96)).put("paddingDp", number(true, 0, 16))
                            .put("thicknessDp", new JSONObject().put("oneOf", new JSONArray().put(type("integer").put("const", 0)).put(number(true, 40, 160))))
                            .put("radiusDp", number(true, 0, 32))
                            .put("backdrop", backdrop().put("description", "Panel override; omit to inherit the global backdrop."))
                            .put("reserveSpace", type("boolean"))
                            .put("hover", object(new JSONObject().put("scale", number(false, 1, 2))
                                    .put("liftDp", number(true, 0, 32)).put("radius", number(false, .5, 3))))))
                    .put("components", type("array").put("items", component).put("minItems", 1).put("maxItems", 24)))
                    .put("required", new JSONArray().put("id").put("components"));
            return object(new JSONObject()
                    .put("version", type("integer").put("const", 5))
                    .put("preset", strings("dark", "light", "contrast"))
                    .put("palette", object(new JSONObject().put("source", enumeration(ShellAppearance.ColorSource.values()))
                            .put("mode", enumeration(ShellAppearance.ColorMode.values())).put("preset", strings("dark", "light", "contrast"))))
                    .put("controls", ShellControlsJson.schema())
                    .put("colors", object(colors))
                    .put("typography", object(new JSONObject().put("font", enumeration(ShellAppearance.Font.values()))
                            .put("scale", number(false, .8, 1.3))))
                    .put("shape", object(new JSONObject().put("radiusScale", number(false, 0, 2)).put("borderDp", number(false, 0, 3))))
                    .put("backdrop", backdrop().put("description", "Global shell backdrop; defaults to opaque with no blur."))
                    .put("composition", object(new JSONObject()
                            .put("panels", type("array").put("items", panel).put("minItems", 1).put("maxItems", 4)
                                    .put("description", "Ordered native panels. Panel IDs and non-spacer component types are unique across the shell."))
                            .put("start", object(new JSONObject()
                                    .put("sections", type("array").put("items", enumeration(ShellComposition.Section.values()))
                                            .put("minItems", 1).put("maxItems", 4).put("uniqueItems", true)
                                            .put("description", "Ordered tabs; apps is required. Unsupported sections are omitted by each host."))
                                    .put("presentation", enumeration(ShellComposition.Presentation.values()))
                                    .put("navigation", enumeration(ShellComposition.Navigation.values())).put("gapDp", number(true, 0, 24))
                                    .put("tileWidthDp", number(true, 80, 200)).put("iconSizeDp", number(true, 24, 64))))))
                    .put("feedback", object(feedback))
                    .put("motion", object(new JSONObject().put("reduced", type("boolean")).put("wallpaper", type("boolean"))
                            .put("panels", enumeration(ShellMotion.Effect.values())).put("taskbar", enumeration(ShellMotion.Effect.values()))
                            .put("durationMs", number(true, 0, 400)).put("feedbackMs", number(true, 0, 250))
                            .put("distanceDp", number(true, 0, 32)).put("scaleFrom", number(false, .85, 1))
                            .put("curve", enumeration(ShellMotion.Curve.values()))))
                    .put("resources", object(new JSONObject().put("icons", object(icons))
                            .put("bundle", type("string").put("pattern", "^(?:[a-f0-9]{64})?$"))
                            .put("iconAssets", object(iconAssets)).put("font", assetPath(true)).put("wallpaper", assetPath(true))
                            .put("shader", ShaderWallpaperJson.schema()))))
                    .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                    .put("title", "MagicDesk shell appearance")
                    .put("description", "Bounded native shell configuration. Missing fields inherit the selected preset and defaults. No commands, paths or scripts.");
        } catch (JSONException error) { throw new ExceptionInInitializerError(error); }
    }

    static JSONObject type(String value) throws JSONException { return new JSONObject().put("type", value); }
    static JSONObject nullable(JSONObject value) throws JSONException {
        return new JSONObject().put("oneOf", new JSONArray().put(type("null")).put(value));
    }
    private static JSONObject backdrop() throws JSONException {
        return object(new JSONObject().put("opacity", number(false, .15, 1).put("default", 1))
                .put("blurRadiusDp", number(true, 0, 64).put("default", 0)));
    }
    static JSONObject assetPath(boolean empty) throws JSONException {
        return type("string").put("maxLength", 160).put("pattern",
                "^(?:[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*/[a-zA-Z0-9_-]+\\.[a-zA-Z0-9]+)" + (empty ? "?" : "") + "$");
    }
    static JSONObject object(JSONObject properties) throws JSONException {
        return type("object").put("properties", properties).put("additionalProperties", false);
    }
    static JSONObject number(boolean integer, double min, double max) throws JSONException {
        return type(integer ? "integer" : "number").put("minimum", min).put("maximum", max);
    }
    private static JSONObject strings(String... values) throws JSONException {
        return type("string").put("enum", new JSONArray(java.util.List.of(values)));
    }
    static JSONObject enumeration(Enum<?>[] values) throws JSONException {
        return strings(java.util.Arrays.stream(values).map(ShellAppearanceSchema::name).toArray(String[]::new));
    }
    static String name(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException((path.isEmpty() ? "/" : path) + ": " + message);
    }

    private static void validate(JSONObject rule, Object value, String path) throws JSONException {
        validate(rule, value, path, false);
    }
    private static void validate(JSONObject rule, Object value, String path, boolean patch) throws JSONException {
        if (rule.has("oneOf")) {
            JSONArray choices = rule.getJSONArray("oneOf");
            IllegalArgumentException last = null;
            for (int i = 0; i < choices.length(); i++) {
                try { validate(choices.getJSONObject(i), value, path, patch); return; }
                catch (IllegalArgumentException error) { last = error; }
            }
            throw last == null ? invalid(path, "no allowed alternative") : last;
        }
        String type = rule.getString("type");
        boolean correct = switch (type) {
            case "null" -> value == JSONObject.NULL;
            case "object" -> value instanceof JSONObject;
            case "array" -> value instanceof JSONArray;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number", "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue())
                    && (!type.equals("integer") || n.doubleValue() == Math.rint(n.doubleValue()));
            default -> throw new IllegalStateException(type);
        };
        if (!correct) throw invalid(path, "expected " + type);
        if (rule.has("const") && Double.compare(((Number) value).doubleValue(), rule.getDouble("const")) != 0) {
            throw invalid(path, "expected " + rule.get("const"));
        }
        if (rule.has("enum")) {
            JSONArray allowed = rule.getJSONArray("enum");
            boolean found = false;
            for (int i = 0; i < allowed.length(); i++) found |= allowed.get(i).equals(value);
            if (!found) throw invalid(path, "expected one of " + allowed);
        }
        if (value instanceof Number n && rule.has("minimum")) {
            double number = n.doubleValue();
            if (number < rule.getDouble("minimum") || number > rule.getDouble("maximum")) {
                throw invalid(path, "must be between " + rule.get("minimum") + " and " + rule.get("maximum"));
            }
        } else if (value instanceof String text) {
            if (text.length() > rule.optInt("maxLength", Integer.MAX_VALUE)) throw invalid(path, "text is too long");
            if (rule.has("pattern") && !text.matches(rule.getString("pattern"))) throw invalid(path, "must match " + rule.getString("pattern"));
        } else if (value instanceof JSONObject object) {
            JSONObject fields = rule.getJSONObject("properties");
            for (var keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                String child = path + "/" + key.replace("~", "~0").replace("/", "~1");
                if (!fields.has(key)) throw invalid(child, "unknown field; allowed: " + fields.names());
                if (!patch || object.get(key) != JSONObject.NULL) validate(fields.getJSONObject(key), object.get(key), child, patch);
            }
            JSONArray required = rule.optJSONArray("required");
            if (!patch && required != null) for (int i = 0; i < required.length(); i++) {
                String key = required.getString(i);
                if (!object.has(key)) throw invalid(path + "/" + key, "required field");
            }
        } else if (value instanceof JSONArray array) {
            if (array.length() < rule.getInt("minItems") || array.length() > rule.getInt("maxItems")) {
                throw invalid(path, "expected " + rule.getInt("minItems") + "-" + rule.getInt("maxItems") + " items");
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                validate(rule.getJSONObject("items"), array.get(i), path + "/" + i);
                if (rule.optBoolean("uniqueItems") && !seen.add(array.get(i).toString())) throw invalid(path + "/" + i, "duplicate item");
            }
        }
    }
}
