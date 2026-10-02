package io.github.mekhontsev.magicdesk;

import java.util.EnumMap;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;
import static io.github.mekhontsev.magicdesk.ShellAppearanceSchema.*;

final class ShellControlsJson {
    static JSONObject schema() throws JSONException {
        JSONObject paint = object(new JSONObject().put("fill", enumeration(UiColor.values()))
                .put("content", enumeration(UiColor.values())).put("outline", enumeration(UiColor.values()))
                .put("layer", enumeration(UiColor.values())).put("layerOpacity", number(false, 0, 1))
                .put("opacity", number(false, 0, 1)));
        JSONObject states = new JSONObject();
        for (var state : ShellControls.State.values()) states.put(name(state), nullable(paint));
        JSONObject style = object(new JSONObject().put("shape", enumeration(ShellControls.Shape.values()))
                .put("radiusDp", number(false, 0, 48)).put("borderDp", number(false, 0, 4))
                .put("paddingHorizontalDp", number(true, 0, 32)).put("paddingVerticalDp", number(true, 0, 24))
                .put("minHeightDp", number(true, 0, 96)).put("textSizeSp", number(false, 8, 32))
                .put("textWeight", number(true, 100, 900)).put("normal", paint).put("states", object(states)));
        JSONObject properties = style.getJSONObject("properties");
        for (var keys = properties.keys(); keys.hasNext();) {
            String key = keys.next(); properties.put(key, nullable(properties.getJSONObject(key)));
        }
        JSONObject roles = new JSONObject();
        for (var role : ShellControls.Role.values()) roles.put(name(role), nullable(style));
        return object(roles);
    }
    static ShellControls parse(JSONObject root) throws JSONException {
        var result = new EnumMap<ShellControls.Role, ShellControls.Style>(ShellControls.Role.class);
        if (root != null) for (var keys = root.keys(); keys.hasNext();) {
            String key = keys.next();
            if (root.isNull(key)) continue;
            JSONObject style = root.getJSONObject(key);
            var states = new EnumMap<ShellControls.State, ShellControls.Paint>(ShellControls.State.class);
            JSONObject input = style.optJSONObject("states");
            if (input != null) for (var items = input.keys(); items.hasNext();) {
                String state = items.next();
                if (!input.isNull(state)) states.put(value(ShellControls.State.class, state), paint(input.getJSONObject(state)));
            }
            result.put(value(ShellControls.Role.class, key), new ShellControls.Style(
                    !style.isNull("shape") ? value(ShellControls.Shape.class, style.getString("shape")) : null,
                    decimal(style, "radiusDp"), decimal(style, "borderDp"), integer(style, "paddingHorizontalDp"),
                    integer(style, "paddingVerticalDp"), integer(style, "minHeightDp"), decimal(style, "textSizeSp"),
                    integer(style, "textWeight"), !style.isNull("normal") ? paint(style.getJSONObject("normal")) : null, states));
        }
        return new ShellControls(result);
    }
    static JSONObject encode(ShellControls controls) throws JSONException {
        JSONObject result = new JSONObject();
        for (var role : ShellControls.Role.values()) result.put(name(role), JSONObject.NULL);
        for (var entry : controls.styles().entrySet()) {
            var s = entry.getValue(); JSONObject states = new JSONObject();
            for (var state : ShellControls.State.values()) states.put(name(state), JSONObject.NULL);
            for (var state : s.states().entrySet()) states.put(name(state.getKey()), paint(state.getValue()));
            result.put(name(entry.getKey()), new JSONObject().put("shape", s.shape() == null ? JSONObject.NULL : name(s.shape()))
                    .put("radiusDp", nullableValue(s.radiusDp())).put("borderDp", nullableValue(s.borderDp()))
                    .put("paddingHorizontalDp", nullableValue(s.paddingHorizontalDp())).put("paddingVerticalDp", nullableValue(s.paddingVerticalDp()))
                    .put("minHeightDp", nullableValue(s.minHeightDp())).put("textSizeSp", nullableValue(s.textSizeSp())).put("textWeight", nullableValue(s.textWeight()))
                    .put("normal", s.normal() == null ? JSONObject.NULL : paint(s.normal())).put("states", states));
        }
        return result;
    }
    private static ShellControls.Paint paint(JSONObject p) {
        return new ShellControls.Paint(value(UiColor.class, p.optString("fill", "transparent")),
                value(UiColor.class, p.optString("content", "text")), value(UiColor.class, p.optString("outline", "transparent")),
                value(UiColor.class, p.optString("layer", "text")), (float) p.optDouble("layerOpacity", 0),
                (float) p.optDouble("opacity", 1));
    }
    private static JSONObject paint(ShellControls.Paint p) throws JSONException {
        return new JSONObject().put("fill", name(p.fill())).put("content", name(p.content())).put("outline", name(p.outline()))
                .put("layer", name(p.layer())).put("layerOpacity", Float.valueOf(p.layerOpacity())).put("opacity", Float.valueOf(p.opacity()));
    }
    private static Object nullableValue(Object value) { return value == null ? JSONObject.NULL : value; }
    private static Integer integer(JSONObject s, String key) throws JSONException { return !s.isNull(key) ? s.getInt(key) : null; }
    private static Float decimal(JSONObject s, String key) throws JSONException { return !s.isNull(key) ? (float) s.getDouble(key) : null; }
    private static <T extends Enum<T>> T value(Class<T> type, String s) { return Enum.valueOf(type, s.toUpperCase(Locale.ROOT)); }
}
