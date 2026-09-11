package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** One explicit display, optionally narrowed to a window or a retained node's subtree. */
record AndroidUiScope(int displayId, int windowId, String rootElementId, int maxNodes,
        AndroidUiSelector selector) {
    static AndroidUiScope parse(final JSONObject args) throws JSONException {
        final int display = AndroidUiSelector.integer(args, "displayId", -1, 0, Integer.MAX_VALUE);
        if (display < 0) throw new IllegalArgumentException("displayId is required");
        final int window = AndroidUiSelector.integer(args, "windowId", -1, 0, Integer.MAX_VALUE);
        String root = null;
        if (args.has("rootElementId")) {
            final Object value = args.get("rootElementId");
            if (!(value instanceof String) || ((String) value).isEmpty()) {
                throw new IllegalArgumentException("rootElementId must be a nonempty handle");
            }
            root = (String) value;
        }
        if (window >= 0 && root != null) {
            throw new IllegalArgumentException("select either windowId or rootElementId, not both");
        }
        return new AndroidUiScope(display, window, root,
                AndroidUiSelector.integer(args, "maxNodes", 200, 1, 256),
                args.has("selector") ? new AndroidUiSelector(args.getJSONObject("selector")) : null);
    }
}
