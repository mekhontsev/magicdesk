package io.github.mekhontsev.magicdesk;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded observation and node handles. Inaccessible or truncated trees never prove absence. */
final class AndroidUiSnapshot implements AutoCloseable {
    private static final Map<String, Integer> ACTIONS = Map.ofEntries(
            Map.entry("click", AccessibilityNodeInfo.ACTION_CLICK),
            Map.entry("long_click", AccessibilityNodeInfo.ACTION_LONG_CLICK),
            Map.entry("focus", AccessibilityNodeInfo.ACTION_FOCUS),
            Map.entry("clear_focus", AccessibilityNodeInfo.ACTION_CLEAR_FOCUS),
            Map.entry("set_text", AccessibilityNodeInfo.ACTION_SET_TEXT),
            Map.entry("select_text", AccessibilityNodeInfo.ACTION_SET_SELECTION),
            Map.entry("scroll_forward", AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
            Map.entry("scroll_backward", AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
            Map.entry("scroll_up", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()),
            Map.entry("scroll_down", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()),
            Map.entry("scroll_left", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()),
            Map.entry("scroll_right", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()),
            Map.entry("show_on_screen", AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()));
    final String id = UUID.randomUUID().toString();
    final long createdAt = SystemClock.uptimeMillis();
    final JSONArray windows = new JSONArray();
    final JSONArray nodes = new JSONArray();
    private final Map<String, Handle> mHandles = new LinkedHashMap<>();
    private final AndroidUiScope mScope;
    private boolean mHierarchyComplete = true;
    private boolean mRedactedMatch;
    private boolean mTextTruncated;
    private boolean mCacheCleared;
    private long mGenerationStart;
    private long mGenerationEnd;
    private int mVisited;
    private int mTextRemaining = 64 * 1024;

    private AndroidUiSnapshot(final AndroidUiScope scope) { mScope = scope; }

    static AndroidUiSnapshot capture(final UiAutomation automation, final AndroidUiScope scope,
            final AccessibilityNodeInfo subtree) throws JSONException {
        final AndroidUiSnapshot snapshot = new AndroidUiSnapshot(scope);
        try {
            // API 34: invalidate cached nodes AND windows before each observation.
            snapshot.mCacheCleared = automation.clearCache();
            final List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(scope.displayId());
            if (windows == null || windows.isEmpty()) snapshot.mHierarchyComplete = false;
            final int windowId = subtree == null ? scope.windowId() : subtree.getWindowId();
            if (windows != null) {
                for (final AccessibilityWindowInfo window : windows) {
                    if (windowId >= 0 && window.getId() != windowId) continue;
                    if (snapshot.windows.length() >= 32) { snapshot.mHierarchyComplete = false; break; }
                    snapshot.captureWindow(window, subtree);
                }
            }
            return snapshot;
        } catch (RuntimeException | JSONException error) {
            snapshot.close();
            throw error;
        }
    }

    private boolean exhausted() {
        return nodes.length() >= mScope.maxNodes() || mVisited >= scanLimit()
                || SystemClock.uptimeMillis() - createdAt > 3000L;
    }

    private int scanLimit() { return mScope.selector() == null ? mScope.maxNodes() : 4096; }

    private void captureWindow(final AccessibilityWindowInfo window, final AccessibilityNodeInfo subtree)
            throws JSONException {
        final Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        final JSONObject data = new JSONObject().put("windowId", window.getId())
                .put("type", window.getType()).put("layer", window.getLayer())
                .put("active", window.isActive()).put("focused", window.isFocused())
                .put("bounds", bounds(bounds)).put("title", preview(window.getTitle()));
        windows.put(data);
        if (exhausted()) {
            mHierarchyComplete = false;
            data.put("rootAvailable", false);
            return;
        }
        final AccessibilityNodeInfo root = subtree == null ? window.getRoot(0) : new AccessibilityNodeInfo(subtree);
        data.put("rootAvailable", root != null);
        if (root == null) { mHierarchyComplete = false; return; }
        final ArrayDeque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(root, null, 0));
        try {
            while (!pending.isEmpty()) {
                if (exhausted()) {
                    mHierarchyComplete = false;
                    break;
                }
                final Pending next = pending.removeFirst();
                final AccessibilityNodeInfo node = next.node;
                mVisited++;
                boolean retained = false;
                try {
                    final JSONObject observed = describe(node);
                    final AndroidUiSelector selector = mScope.selector();
                    if (selector != null && selector.couldMatchRedacted(observed)) mRedactedMatch = true;
                    String elementId = null;
                    // Match the complete accessibility value, not its shortened wire preview.
                    if (selector == null || selector.matches(observed)) {
                        elementId = id + ":" + nodes.length();
                        mHandles.put(elementId, new Handle(node, identity(node)));
                        retained = true;
                        previewField(observed, "text");
                        previewField(observed, "description");
                        nodes.put(observed.put("elementId", elementId).put("depth", next.depth)
                                .put("parentId", next.parent == null ? JSONObject.NULL : next.parent));
                    }
                    if (next.depth >= 40) {
                        if (node.getChildCount() > 0) mHierarchyComplete = false;
                        continue;
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        if (exhausted() || mVisited + pending.size() >= scanLimit()) { mHierarchyComplete = false; break; }
                        final AccessibilityNodeInfo child = node.getChild(i, 0);
                        if (child == null) mHierarchyComplete = false;
                        else pending.addLast(new Pending(child, elementId, next.depth + 1));
                    }
                } finally {
                    if (!retained) node.recycle();
                }
            }
        } finally {
            for (final Pending remaining : pending) remaining.node.recycle();
        }
    }

    private JSONObject describe(final AccessibilityNodeInfo node) throws JSONException {
        final Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        final JSONArray actions = new JSONArray();
        for (final var entry : ACTIONS.entrySet()) {
            if (supports(node, entry.getValue())) actions.put(entry.getKey());
        }
        return new JSONObject().put("windowId", node.getWindowId())
                .put("package", raw(node.getPackageName())).put("className", raw(node.getClassName()))
                .put("resourceId", raw(node.getViewIdResourceName())).put("uniqueId", raw(node.getUniqueId()))
                .put("text", node.isPassword() ? JSONObject.NULL : raw(node.getText()))
                .put("description", node.isPassword() ? JSONObject.NULL : raw(node.getContentDescription()))
                .put("childCount", node.getChildCount())
                .put("password", node.isPassword()).put("bounds", bounds(bounds))
                .put("enabled", node.isEnabled()).put("visible", node.isVisibleToUser())
                .put("focused", node.isFocused()).put("selected", node.isSelected())
                .put("checked", node.isChecked()).put("editable", node.isEditable())
                .put("scrollable", node.isScrollable()).put("actions", actions);
    }

    void observedBetween(final long start, final long end) {
        mGenerationStart = start;
        mGenerationEnd = end;
    }

    boolean stable() { return mCacheCleared && mGenerationStart == mGenerationEnd; }

    boolean complete() { return stable() && mHierarchyComplete && !mRedactedMatch; }

    JSONObject metadata() throws JSONException {
        return new JSONObject().put("snapshotId", id).put("displayId", mScope.displayId())
                .put("windowId", mScope.windowId() < 0 ? JSONObject.NULL : mScope.windowId())
                .put("rootElementId", mScope.rootElementId() == null ? JSONObject.NULL : mScope.rootElementId())
                .put("complete", complete()).put("hierarchyComplete", mHierarchyComplete)
                .put("stable", stable()).put("cacheCleared", mCacheCleared)
                .put("generationStart", mGenerationStart).put("generationEnd", mGenerationEnd)
                .put("textTruncated", mTextTruncated).put("redactedMatchPossible", mRedactedMatch)
                .put("visitedNodes", mVisited).put("handleLifetimeMillis", 60000)
                .put("capturedAtUptimeMillis", createdAt);
    }

    JSONObject toJson() throws JSONException {
        return metadata().put("windows", windows).put("nodes", nodes);
    }

    private Handle handle(final String elementId) {
        final Handle handle = mHandles.get(elementId);
        if (handle == null) throw new IllegalArgumentException("stale UI element; inspect the current UI again");
        return handle;
    }

    AccessibilityNodeInfo refreshedNode(final String elementId, final int displayId) {
        if (displayId != mScope.displayId()) throw new IllegalArgumentException("UI handle belongs to another display");
        final Handle handle = handle(elementId);
        // Actions and subtree refreshes must not mutate the retained text revision.
        final AccessibilityNodeInfo node = new AccessibilityNodeInfo(handle.node);
        // Virtualized lists may reuse a node id for different content. Never silently click that row.
        try {
            if (!node.refresh() || !handle.identity.matches(identity(node))) throw stale();
            return node;
        } catch (RuntimeException error) {
            node.recycle();
            throw error;
        }
    }

    JSONObject readText(final String elementId, final JSONObject args) throws JSONException {
        final AccessibilityNodeInfo node = handle(elementId).node;
        final String field = args.optString("field", "text");
        if (!field.equals("text") && !field.equals("description")) {
            throw new IllegalArgumentException("field must be text or description");
        }
        return AndroidUiText.page(field.equals("text") ? node.getText() : node.getContentDescription(),
                node.isPassword(), args).put("elementId", elementId).put("snapshotId", id)
                .put("field", field).put("capturedAtUptimeMillis", createdAt);
    }

    JSONObject perform(final String elementId, final JSONObject args) throws JSONException {
        final AccessibilityNodeInfo node = refreshedNode(elementId, mScope.displayId());
        try {
            return perform(node, elementId, args);
        } finally {
            node.recycle();
        }
    }

    private JSONObject perform(final AccessibilityNodeInfo node, final String elementId,
            final JSONObject args) throws JSONException {
        final String action = args.getString("action");
        final Integer code = ACTIONS.get(action);
        if (code == null) throw new IllegalArgumentException("unsupported UI action: " + action);
        if (!node.isEnabled() || !supports(node, code)) {
            throw new IllegalArgumentException("UI element does not currently support " + action);
        }
        if (!node.isVisibleToUser() && !action.equals("show_on_screen")) {
            throw new IllegalArgumentException("UI element is not visible");
        }
        final Bundle arguments = new Bundle();
        if (action.equals("set_text")) {
            final String value = args.getString("text");
            if (value.length() > 32768) throw new IllegalArgumentException("text exceeds 32768 characters");
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        } else if (action.equals("select_text")) {
            final int start = AndroidUiSelector.integer(args, "start", -1, 0, 32768);
            final int end = AndroidUiSelector.integer(args, "end", -1, 0, 32768);
            if (start < 0 || end < start) throw new IllegalArgumentException("invalid text selection");
            arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
            arguments.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
        }
        // Android accepting an action is not proof of its visual completion; ui.wait verifies that.
        final boolean accepted = node.performAction(code, arguments);
        return new JSONObject().put("accepted", accepted).put("elementId", elementId).put("action", action);
    }

    private static IllegalArgumentException stale() {
        return new IllegalArgumentException("UI element identity changed; inspect the current UI again");
    }

    private static boolean supports(final AccessibilityNodeInfo node, final int action) {
        for (final var supported : node.getActionList()) if (supported.getId() == action) return true;
        return false;
    }

    private void previewField(final JSONObject data, final String key) throws JSONException {
        if (data.isNull(key)) {
            data.put(key + "Length", JSONObject.NULL).put(key + "Truncated", false);
            return;
        }
        final String value = data.getString(key);
        final String shortValue = preview(value);
        data.put(key, shortValue).put(key + "Length", value.length())
                .put(key + "Truncated", shortValue.length() < value.length());
    }

    private String preview(final CharSequence value) {
        if (value == null) return "";
        int length = Math.min(value.length(), Math.min(512, mTextRemaining));
        if (length > 0 && length < value.length() && Character.isHighSurrogate(value.charAt(length - 1))
                && Character.isLowSurrogate(value.charAt(length))) length--;
        if (length < value.length()) mTextTruncated = true;
        mTextRemaining -= length;
        return value.subSequence(0, length).toString();
    }

    private static String raw(final CharSequence value) { return value == null ? "" : value.toString(); }

    private static AndroidUiIdentity identity(final AccessibilityNodeInfo node) {
        return new AndroidUiIdentity(raw(node.getPackageName()), raw(node.getClassName()),
                raw(node.getViewIdResourceName()), raw(node.getUniqueId()), node.getWindowId(), node.isEditable(),
                node.isPassword() ? "" : raw(node.getText()), node.isPassword() ? "" : raw(node.getContentDescription()));
    }

    private static JSONObject bounds(final Rect value) throws JSONException {
        return new JSONObject().put("left", value.left).put("top", value.top)
                .put("right", value.right).put("bottom", value.bottom);
    }

    @Override public void close() {
        for (final Handle handle : mHandles.values()) handle.node.recycle();
        mHandles.clear();
    }

    private record Pending(AccessibilityNodeInfo node, String parent, int depth) { }
    private record Handle(AccessibilityNodeInfo node, AndroidUiIdentity identity) { }
}
