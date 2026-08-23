package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/** Semantic automation view of the live desktop UI. Accessed on the UI thread. */
final class DesktopAutomationUiRegistry {
    static final class Snapshot {
        static final Snapshot UNAVAILABLE = new Snapshot(false, -1,
                new JSONArray());

        final boolean available;
        final int displayId;
        final JSONArray elements;

        Snapshot(
                final boolean available,
                final int displayId,
                final JSONArray elements) {
            this.available = available;
            this.displayId = displayId;
            this.elements = elements == null ? new JSONArray() : elements;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("available", available)
                    .put("displayId", displayId)
                    .put("elements", elements)
                    .put("count", elements.length());
        }
    }

    static final class ActionResult {
        final boolean accepted;
        final String message;
        final JSONObject element;

        ActionResult(
                final boolean accepted,
                final String message,
                final JSONObject element) {
            this.accepted = accepted;
            this.message = message == null ? "" : message;
            this.element = element == null ? new JSONObject() : element;
        }
    }

    private final Map<View, Entry> mEntries = new WeakHashMap<>();

    void register(
            final View view,
            final String id,
            final String role,
            final CharSequence label) {
        register(view, id, role, label, "", -1);
    }

    void register(
            final View view,
            final String id,
            final String role,
            final CharSequence label,
            final String packageName,
            final int taskId) {
        if (view == null || clean(id).isEmpty()) {
            return;
        }
        mEntries.put(view, new Entry(
                clean(id),
                clean(role),
                clean(label == null ? "" : label.toString()),
                clean(packageName),
                taskId));
    }

    Snapshot snapshot(
            final int displayId,
            final String query,
            final boolean includeHidden) throws JSONException {
        final String normalizedQuery = clean(query).toLowerCase(Locale.ROOT);
        final Map<String, JSONObject> byId = new TreeMap<>();
        for (final Map.Entry<View, Entry> registered : mEntries.entrySet()) {
            final View view = registered.getKey();
            final Entry entry = registered.getValue();
            if (view == null || entry == null || !view.isAttachedToWindow()) {
                continue;
            }
            final JSONObject element = elementJson(view, entry);
            final boolean visible = element.getBoolean("visible");
            if ((!includeHidden && !visible)
                    || !matches(entry, normalizedQuery)) {
                continue;
            }
            final JSONObject previous = byId.get(entry.id);
            if (previous == null
                    || (!previous.optBoolean("visible") && visible)) {
                byId.put(entry.id, element);
            }
        }
        final JSONArray elements = new JSONArray();
        for (final JSONObject element : byId.values()) {
            elements.put(element);
        }
        return new Snapshot(true, displayId, elements);
    }

    ActionResult invoke(
            final String elementId,
            final String rawAction) throws JSONException {
        final String id = clean(elementId);
        final String action = clean(rawAction).toLowerCase(Locale.ROOT);
        View hiddenMatch = null;
        Entry hiddenEntry = null;
        for (final Map.Entry<View, Entry> registered : mEntries.entrySet()) {
            final View view = registered.getKey();
            final Entry entry = registered.getValue();
            if (view == null || entry == null
                    || !view.isAttachedToWindow()
                    || !entry.id.equals(id)) {
                continue;
            }
            if (elementJson(view, entry).getBoolean("visible")) {
                return invoke(view, entry, action);
            }
            hiddenMatch = view;
            hiddenEntry = entry;
        }
        if (hiddenMatch != null) {
            return new ActionResult(
                    false,
                    "UI element is not visible",
                    elementJson(hiddenMatch, hiddenEntry));
        }
        return new ActionResult(false, "UI element not found", null);
    }

    private static ActionResult invoke(
            final View view,
            final Entry entry,
            final String action) throws JSONException {
        final JSONObject before = elementJson(view, entry);
        if (!view.isEnabled()) {
            return new ActionResult(false, "UI element is disabled", before);
        }
        final boolean accepted;
        switch (action) {
            case "click":
                accepted = view.hasOnClickListeners() && view.performClick();
                break;
            case "secondary_click":
                accepted = view.isLongClickable() && view.performLongClick();
                break;
            default:
                return new ActionResult(false, "unsupported UI action", before);
        }
        return new ActionResult(
                accepted,
                accepted ? "UI action accepted" : "UI action was not handled",
                before);
    }

    private static JSONObject elementJson(
            final View view,
            final Entry entry) throws JSONException {
        final Rect bounds = new Rect();
        final boolean visible = isVisible(view)
                && view.getGlobalVisibleRect(bounds)
                && !bounds.isEmpty();
        final JSONArray actions = new JSONArray();
        if (view.hasOnClickListeners()) {
            actions.put("click");
        }
        if (view.isLongClickable()) {
            actions.put("secondary_click");
        }
        final JSONObject result = new JSONObject()
                .put("id", entry.id)
                .put("role", entry.role)
                .put("label", entry.label)
                .put("visible", visible)
                .put("enabled", view.isEnabled())
                .put("focused", view.hasFocus())
                .put("selected", view.isSelected() || view.isActivated())
                .put("bounds", rectJson(visible ? bounds : new Rect()))
                .put("actions", actions);
        if (!entry.packageName.isEmpty()) {
            result.put("package", entry.packageName);
        }
        if (entry.taskId >= 0) {
            result.put("taskId", entry.taskId);
        }
        return result;
    }

    private static boolean isVisible(final View view) {
        return view.isAttachedToWindow()
                && view.isShown()
                && view.getAlpha() > 0f;
    }

    private static boolean matches(
            final Entry entry,
            final String normalizedQuery) {
        return normalizedQuery.isEmpty()
                || entry.id.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || entry.role.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || entry.label.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || entry.packageName.toLowerCase(Locale.ROOT)
                        .contains(normalizedQuery);
    }

    private static JSONObject rectJson(final Rect bounds)
            throws JSONException {
        return new JSONObject()
                .put("left", bounds.left)
                .put("top", bounds.top)
                .put("right", bounds.right)
                .put("bottom", bounds.bottom);
    }

    static String segment(final String value) {
        final String normalized = clean(value).toLowerCase(Locale.ROOT);
        final StringBuilder result = new StringBuilder(normalized.length());
        boolean separator = false;
        for (int index = 0; index < normalized.length(); index++) {
            final char character = normalized.charAt(index);
            if (Character.isLetterOrDigit(character)
                    || character == '.' || character == '_' || character == '-') {
                result.append(character);
                separator = false;
            } else if (!separator && result.length() > 0) {
                result.append('-');
                separator = true;
            }
        }
        while (result.length() > 0
                && result.charAt(result.length() - 1) == '-') {
            result.setLength(result.length() - 1);
        }
        return result.length() == 0 ? "item" : result.toString();
    }

    private static String clean(final String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Entry {
        final String id;
        final String role;
        final String label;
        final String packageName;
        final int taskId;

        Entry(
                final String id,
                final String role,
                final String label,
                final String packageName,
                final int taskId) {
            this.id = id;
            this.role = role;
            this.label = label;
            this.packageName = packageName;
            this.taskId = taskId;
        }
    }
}
