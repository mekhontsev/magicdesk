package io.github.mekhontsev.magicdesk;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Sparse objects inherit global fields; arrays are complete replacements, never index patches. */
final class WorkspaceAppearancePatch {
    static final WorkspaceAppearancePatch EMPTY = new WorkspaceAppearancePatch("{}");
    private final String mJson;

    private WorkspaceAppearancePatch(String json) { mJson = json; }

    static WorkspaceAppearancePatch parse(String text) throws JSONException {
        JSONObject value = object(text, ShellAppearanceJson.MAX_BYTES);
        if (value.has("preset")) {
            throw ShellAppearanceSchema.invalid("/preset", "workspace patches inherit global defaults, not a preset");
        }
        ShellAppearanceSchema.validatePatch(value);
        return value.length() == 0 ? EMPTY : new WorkspaceAppearancePatch(value.toString());
    }

    ShellAppearance resolve(ShellAppearance defaults) throws JSONException {
        if (isEmpty()) return defaults;
        JSONObject merged = ShellAppearanceJson.encode(defaults);
        merge(merged, new JSONObject(mJson));
        return ShellAppearanceJson.parse(merged.toString());
    }

    String json() { return mJson; }
    boolean isEmpty() { return mJson.equals("{}"); }

    static JSONObject object(String text, int limit) throws JSONException {
        if (text == null || bytes(text) > limit) {
            throw ShellAppearanceSchema.invalid("", "missing JSON object or byte limit exceeded (" + limit + ")");
        }
        JSONTokener reader = new JSONTokener(text) {
            private int depth;
            @Override public Object nextValue() throws JSONException {
                if (++depth > 16) throw new JSONException("Appearance nesting is too deep");
                try { return super.nextValue(); } finally { depth--; }
            }
        };
        JSONObject value = new JSONObject(reader);
        if (reader.nextClean() != 0) throw ShellAppearanceSchema.invalid("", "trailing JSON content");
        return value;
    }

    static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }

    private static void merge(JSONObject base, JSONObject patch) throws JSONException {
        for (var keys = patch.keys(); keys.hasNext();) {
            String key = keys.next();
            Object value = patch.get(key);
            if (value == JSONObject.NULL) base.remove(key);
            else if (value instanceof JSONObject child) {
                JSONObject inherited = base.optJSONObject(key);
                if (inherited == null) { inherited = new JSONObject(); base.put(key, inherited); }
                merge(inherited, child);
            } else base.put(key, value);
        }
    }
}
