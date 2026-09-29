package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native-family predicates over event-triggered inspections, never render acquisition. */
final class AutomationGraphicsFamilyObservation {
    static void validate(String condition, JSONObject args) {
        boolean family = condition.equals("graphics_family_present") || condition.equals("graphics_family_absent");
        if (!family && (args.has("memberId") || args.has("memberType") || args.has("memberParentId")))
            throw new IllegalArgumentException("Family member selectors require a graphics_family condition");
        if (!family) return;
        if (AutomationJsonArguments.requiredLong(args, "windowId") <= 0)
            throw new IllegalArgumentException("Expected positive family windowId");
        if (!args.has("memberId") && !args.has("memberType"))
            throw new IllegalArgumentException("Select memberId or memberType");
        if (args.has("memberId") && AutomationJsonArguments.requiredLong(args, "memberId") <= 0)
            throw new IllegalArgumentException("Expected positive native memberId");
        if (args.has("memberParentId") && AutomationJsonArguments.requiredLong(args, "memberParentId") <= 0)
            throw new IllegalArgumentException("Expected positive native memberParentId");
        if (args.has("memberType") && (!(args.opt("memberType") instanceof String type)
                || !java.util.Set.of("normal", "dialog", "menu", "dropdown", "popup", "tooltip", "splash",
                    "utility", "other", "unknown",
                    "owner", "subsurface").contains(type)))
            throw new IllegalArgumentException("Unknown native memberType");
    }

    static JSONObject observe(String condition, JSONObject args) {
        try {
            String id = args.getString("sessionId");
            var session = GraphicalSessions.find(id);
            var result = new JSONObject().put("condition", condition).put("matched", false);
            boolean absent = condition.equals("graphics_family_absent");
            if (session == null || session.stopped()) return result.put("matched", absent).put("sessionPresent", false);
            if (!session.ready()) return result;
            var inspected = AutomationGraphicsInspection.inspect(new JSONObject().put("sessionId", id)
                    .put("windowId", args.getLong("windowId")).put("limit", 256));
            if (!inspected.success) throw new IllegalStateException(inspected.message);
            var matching = matching(inspected.data, args);
            return result.put("family", inspected.data).put("matchingMembers", matching)
                    .put("matched", matches(inspected.data, matching, absent));
        } catch (JSONException error) { throw new IllegalArgumentException(error); }
    }

    static boolean matches(JSONObject family, JSONArray matching, boolean absent) {
        return absent ? !family.optBoolean("truncated", true) && matching.length() == 0 : matching.length() > 0;
    }

    static JSONArray matching(JSONObject family, JSONObject args) throws JSONException {
        var result = new JSONArray();
        var nodes = family.getJSONArray("windows");
        for (int i = 0; i < nodes.length(); i++) {
            var node = nodes.getJSONObject(i);
            if (!node.optBoolean("mapped")) continue;
            if (args.has("memberId") && node.getLong("id") != args.getLong("memberId")) continue;
            if (args.has("memberType") && !node.getString("type").equals(args.getString("memberType"))) continue;
            if (args.has("memberParentId") && node.optLong("transientFor", node.optLong("parentId"))
                    != args.getLong("memberParentId")) continue;
            result.put(node);
        }
        return result;
    }
    private AutomationGraphicsFamilyObservation() { }
}
