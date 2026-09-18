package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** One explicit display or task, optionally narrowed to a window or retained subtree. */
record AndroidUiScope(int displayId, int taskId, int windowId, String rootElementId, int maxNodes,
        AndroidUiSelector selector) {
    static AndroidUiScope parse(final JSONObject args) throws JSONException {
        final int display = AndroidUiSelector.integer(args, "displayId", -1, 0, Integer.MAX_VALUE);
        final int task = AndroidUiSelector.integer(args, "taskId", -1, 0, Integer.MAX_VALUE);
        if ((display >= 0) == (task >= 0)) throw new IllegalArgumentException("select exactly one of displayId or taskId");
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
        return new AndroidUiScope(display, task, window, root,
                AndroidUiSelector.integer(args, "maxNodes", 200, 1, 256),
                args.has("selector") ? new AndroidUiSelector(args.getJSONObject("selector")) : null);
    }

    boolean contains(int display, Integer task) {
        return taskId >= 0 ? task != null && task == taskId : displayId == display;
    }
}
