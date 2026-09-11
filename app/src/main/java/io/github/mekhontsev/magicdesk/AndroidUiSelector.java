package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Set;

/** Exact, conjunctive matching. No regex, coordinate guessing or ambiguous first-match actions. */
final class AndroidUiSelector {
    private static final Set<String> TEXT = Set.of(
            "package", "resourceId", "className", "text", "description");
    private static final Set<String> FLAGS = Set.of(
            "enabled", "visible", "focused", "selected", "checked", "editable", "scrollable");
    private final JSONObject mCriteria;

    AndroidUiSelector(final JSONObject criteria) throws JSONException {
        if (criteria == null || criteria.length() == 0) {
            throw new IllegalArgumentException("selector must contain at least one criterion");
        }
        final var keys = criteria.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final Object value = criteria.get(key);
            if (TEXT.contains(key)) {
                if (!(value instanceof String) || ((String) value).length() > 32768) {
                    throw new IllegalArgumentException("invalid selector string: " + key);
                }
            } else if (FLAGS.contains(key)) {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException("invalid selector boolean: " + key);
                }
            } else {
                throw new IllegalArgumentException("unsupported selector criterion: " + key);
            }
        }
        mCriteria = new JSONObject(criteria.toString());
    }

    boolean matches(final JSONObject node) {
        final var keys = mCriteria.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (!mCriteria.opt(key).equals(node.opt(key))) return false;
        }
        return true;
    }

    boolean couldMatchRedacted(final JSONObject node) {
        if (!node.optBoolean("password") || !mCriteria.has("text") && !mCriteria.has("description")) return false;
        final var keys = mCriteria.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (!key.equals("text") && !key.equals("description")
                    && !mCriteria.opt(key).equals(node.opt(key))) return false;
        }
        return true;
    }

    static boolean satisfied(final boolean present, final int matches, final boolean complete,
            final boolean stable) {
        return stable && (present ? matches > 0 : complete && matches == 0);
    }

    static int integer(final JSONObject args, final String key, final int fallback,
            final int minimum, final int maximum) {
        final Object value = args.opt(key);
        if (value == null) return fallback;
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                || ((Number) value).intValue() < minimum || ((Number) value).intValue() > maximum) {
            throw new IllegalArgumentException(key + " must be an integer from " + minimum + " to " + maximum);
        }
        return ((Number) value).intValue();
    }
}
