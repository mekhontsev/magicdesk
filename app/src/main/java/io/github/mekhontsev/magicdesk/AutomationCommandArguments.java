package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Basic argument shape shared by adapters; each operation owns its semantic checks. */
final class AutomationCommandArguments {
    private AutomationCommandArguments() { }

    static JSONObject command(String name) throws JSONException {
        final JSONArray catalog = AutomationCommandCatalog.create();
        for (int i = 0; i < catalog.length(); i++) {
            final JSONObject command = catalog.getJSONObject(i);
            if (command.getString("name").equals(name)) return command;
        }
        throw new IllegalArgumentException("Unknown command: " + name);
    }

    static void check(String name, JSONObject arguments) throws JSONException {
        final JSONObject schema = command(name).getJSONObject("inputSchema");
        final JSONObject args = arguments == null ? new JSONObject() : arguments;
        final JSONObject properties = schema.getJSONObject("properties");
        final JSONArray required = schema.optJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.length(); i++) {
                final String key = required.getString(i);
                if (!args.has(key)) throw new IllegalArgumentException("Missing argument: " + key);
            }
        }
        final var keys = args.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final JSONObject property = properties.optJSONObject(key);
            if (property == null) throw new IllegalArgumentException("Unknown argument: " + key);
            final Object value = args.get(key);
            if (!matches(property.optString("type"), value)) {
                throw new IllegalArgumentException(key + " must be " + property.optString("type"));
            }
            final JSONArray choices = property.optJSONArray("enum");
            if (choices != null) {
                boolean found = false;
                for (int i = 0; i < choices.length(); i++) found |= choices.get(i).equals(value);
                if (!found) throw new IllegalArgumentException(key + " must be one of " + choices);
            }
        }
    }

    static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.rint(number.doubleValue());
            case "number" -> value instanceof Number number && Double.isFinite(number.doubleValue());
            case "object" -> value instanceof JSONObject;
            case "array" -> value instanceof JSONArray;
            default -> false;
        };
    }
}
