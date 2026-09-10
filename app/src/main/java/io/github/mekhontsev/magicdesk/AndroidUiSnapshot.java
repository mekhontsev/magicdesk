package io.github.mekhontsev.magicdesk;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;
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
    private final Map<String, JSONObject> mObserved = new LinkedHashMap<>();
    boolean complete = true;
    private int mTextRemaining = 64 * 1024;

    static AndroidUiSnapshot capture(final UiAutomation automation, final int displayId,
            final int maxNodes) throws JSONException {
        final AndroidUiSnapshot snapshot = new AndroidUiSnapshot();
        try {
            final SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
            final List<AccessibilityWindowInfo> windows = all.get(displayId);
            if (windows == null || windows.isEmpty()) snapshot.complete = false;
            if (windows != null) {
                for (final AccessibilityWindowInfo window : windows) {
                    if (snapshot.windows.length() >= 32) { snapshot.complete = false; break; }
                    snapshot.captureWindow(window, maxNodes);
                }
            }
            return snapshot;
        } catch (RuntimeException | JSONException error) {
            snapshot.close();
            throw error;
        }
    }

    private void captureWindow(final AccessibilityWindowInfo window, final int maxNodes)
            throws JSONException {
        final Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        final JSONObject data = new JSONObject().put("windowId", window.getId())
                .put("type", window.getType()).put("layer", window.getLayer())
                .put("active", window.isActive()).put("focused", window.isFocused())
                .put("bounds", bounds(bounds)).put("title", text(window.getTitle()));
        windows.put(data);
        if (nodes.length() >= maxNodes || SystemClock.uptimeMillis() - createdAt > 3000L) {
            complete = false;
            data.put("rootAvailable", false);
            return;
        }
        final AccessibilityNodeInfo root = window.getRoot(0);
        data.put("rootAvailable", root != null);
        if (root == null) { complete = false; return; }
        final ArrayDeque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(root, null, 0));
        try {
            while (!pending.isEmpty()) {
                if (nodes.length() >= maxNodes || SystemClock.uptimeMillis() - createdAt > 3000L) {
                    complete = false;
                    break;
                }
                final Pending next = pending.removeFirst();
                final AccessibilityNodeInfo node = next.node;
                final String elementId = id + ":" + nodes.length();
                mHandles.put(elementId, new Handle(node, identity(node)));
                final JSONObject observed = describe(node).put("elementId", elementId)
                        .put("parentId", next.parent == null ? JSONObject.NULL : next.parent);
                nodes.put(observed);
                mObserved.put(elementId, observed);
                if (next.depth >= 40) {
                    if (node.getChildCount() > 0) complete = false;
                    continue;
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    if (nodes.length() + pending.size() >= maxNodes) { complete = false; break; }
                    final AccessibilityNodeInfo child = node.getChild(i, 0);
                    if (child == null) complete = false;
                    else pending.addLast(new Pending(child, elementId, next.depth + 1));
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
                .put("package", text(node.getPackageName())).put("className", text(node.getClassName()))
                .put("resourceId", text(node.getViewIdResourceName())).put("uniqueId", text(node.getUniqueId()))
                .put("text", node.isPassword() ? JSONObject.NULL : text(node.getText()))
                .put("description", node.isPassword() ? JSONObject.NULL : text(node.getContentDescription()))
                .put("password", node.isPassword()).put("bounds", bounds(bounds))
                .put("enabled", node.isEnabled()).put("visible", node.isVisibleToUser())
                .put("focused", node.isFocused()).put("selected", node.isSelected())
                .put("checked", node.isChecked()).put("editable", node.isEditable())
                .put("scrollable", node.isScrollable()).put("actions", actions);
    }

    JSONObject toJson(final int displayId) throws JSONException {
        return new JSONObject().put("snapshotId", id).put("displayId", displayId)
                .put("complete", complete).put("windows", windows).put("nodes", nodes)
                .put("handleLifetimeMillis", 60000).put("capturedAtUptimeMillis", createdAt);
    }

    JSONArray matches(final AndroidUiSelector selector) {
        final JSONArray result = new JSONArray();
        for (final JSONObject node : mObserved.values()) {
            if (selector.matches(node)) result.put(node);
        }
        return result;
    }

    boolean completeFor(final AndroidUiSelector selector) {
        if (!complete) return false;
        for (final JSONObject node : mObserved.values()) if (selector.couldMatchRedacted(node)) return false;
        return true;
    }

    JSONObject perform(final String elementId, final JSONObject args) throws JSONException {
        final Handle handle = mHandles.get(elementId);
        if (handle == null) {
            throw new IllegalArgumentException("stale UI element; inspect the current UI again");
        }
        final AccessibilityNodeInfo node = handle.node;
        // Virtualized lists may reuse a node id for different content. Never silently click that row.
        if (!node.refresh() || !handle.identity.matches(identity(node))) {
            mHandles.remove(elementId);
            node.recycle();
            throw stale();
        }
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

    private String text(final CharSequence value) {
        if (value == null) return "";
        final int length = Math.min(value.length(), Math.min(512, mTextRemaining));
        if (length < value.length()) complete = false;
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
        mObserved.clear();
    }

    private record Pending(AccessibilityNodeInfo node, String parent, int depth) { }
    private record Handle(AccessibilityNodeInfo node, AndroidUiIdentity identity) { }
}
