package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded, declarative user interaction. Actions are results, never executable commands. */
record UserInteractionRequest(String kind, String title, String message, String type,
        String initialText, boolean multiple, List<Item> items, int displayId,
        long lifetimeMillis) {
    record Item(String id, String label, boolean reply) { }

    static UserInteractionRequest parse(String kind, JSONObject args) throws JSONException {
        final boolean dialog = "dialog".equals(kind);
        final String title = text(args, "title", "", 160);
        if (title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        final String type = dialog ? args.getString("type") : "notification";
        if (dialog && !List.of("text", "confirm", "choice").contains(type)) {
            throw new IllegalArgumentException("type must be text, confirm or choice");
        }
        final String initial = text(args, "initialText", "", 8192);
        if (args.has("initialText") && !"text".equals(type)) {
            throw new IllegalArgumentException("initialText requires a text dialog");
        }
        if (args.has("multiple") && !"choice".equals(type)) {
            throw new IllegalArgumentException("multiple requires a choice dialog");
        }
        final String field = dialog ? "choices" : "actions";
        final JSONArray values = args.optJSONArray(field);
        final List<Item> items = new ArrayList<>();
        final HashSet<String> ids = new HashSet<>();
        if (!dialog) ids.add("open"); // The notification body is itself an action.
        if (values != null) {
            if (dialog && !"choice".equals(type)) {
                throw new IllegalArgumentException("choices requires a choice dialog");
            }
            if (values.length() > (dialog ? 32 : 3)) {
                throw new IllegalArgumentException("too many " + field);
            }
            for (int i = 0; i < values.length(); i++) {
                final JSONObject item = values.getJSONObject(i);
                final var keys = item.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    if (!List.of("id", "label").contains(key) && !("reply".equals(key) && !dialog)) {
                        throw new IllegalArgumentException("unknown " + field + " item field: " + key);
                    }
                }
                final String id = text(item, "id", "", 80);
                final String label = text(item, "label", "", 160);
                if (id.isBlank() || label.isBlank() || !ids.add(id)) {
                    throw new IllegalArgumentException("items require distinct ids and nonblank labels; open is reserved");
                }
                if (item.has("reply") && !(item.get("reply") instanceof Boolean)) {
                    throw new IllegalArgumentException("reply must be boolean");
                }
                items.add(new Item(id, label, item.optBoolean("reply", false)));
            }
        }
        if ("choice".equals(type) && items.isEmpty()) {
            throw new IllegalArgumentException("a choice dialog requires choices");
        }
        final long display = args.optLong("displayId", 0);
        if (display < 0 || display > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid displayId");
        final long lifetime = args.optLong("lifetimeMillis", 3_600_000);
        if (lifetime < 1000 || lifetime > 86_400_000) {
            throw new IllegalArgumentException("lifetimeMillis must be between 1000 and 86400000");
        }
        return new UserInteractionRequest(kind, title, text(args, "message", "", 4096), type,
                initial, args.optBoolean("multiple", false), List.copyOf(items), (int) display, lifetime);
    }

    private static String text(JSONObject args, String key, String fallback, int limit) throws JSONException {
        if (!args.has(key)) return fallback;
        if (!(args.get(key) instanceof String value) || value.length() > limit) {
            throw new IllegalArgumentException(key + " must be text up to " + limit + " characters");
        }
        return value;
    }
}
