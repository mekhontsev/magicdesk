package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One workspace owner binds every native panel to the existing chrome token. */
final class DesktopTaskbarHost {
    record Panel(String id, View view, ShellPanel.Edge edge, Rect content, Rect paint, Rect output, int overflow) {
        Panel {
            java.util.Objects.requireNonNull(id); java.util.Objects.requireNonNull(view); java.util.Objects.requireNonNull(edge);
            content = new Rect(content); paint = new Rect(paint); output = new Rect(output);
            if (content.isEmpty() || !paint.contains(content)) throw new IllegalArgumentException("Invalid native panel bounds");
        }
        Rect frame() {
            var expanded = PanelGeometry.expanded(bounds(paint), bounds(output), overflow);
            return new Rect(expanded.left(), expanded.top(), expanded.right(), expanded.bottom());
        }
    }
    interface BoundsListener { void onBoundsChanged(List<Rect> bounds); }
    interface EdgeInputListener { void onEdgeInput(MotionEvent event); }
    private static final Object REGISTRY_LOCK = new Object();
    private static final Map<Integer, DesktopTaskbarHost> HOSTS = new HashMap<>();
    private static final Map<Integer, DesktopChromeActivity> ACTIVITIES = new HashMap<>();
    private final int mDisplayId;
    private final BoundsListener mBoundsListener;
    private List<Panel> mPanels = List.of();
    private boolean mPresented = true, mEdgeHidden, mReleased;
    private int mEdgeHeight = 1;
    private EdgeInputListener mEdgeInputListener;

    DesktopTaskbarHost(int displayId, BoundsListener boundsListener) {
        if (displayId == Display.INVALID_DISPLAY) throw new IllegalArgumentException("Native panels require a display");
        mDisplayId = displayId; mBoundsListener = boundsListener;
    }
    boolean attachPanels(List<Panel> panels) {
        if (mReleased || panels.isEmpty()) return false;
        synchronized (REGISTRY_LOCK) { HOSTS.put(mDisplayId, this); }
        if (mPanels.equals(panels)) apply(currentActivity());
        else updatePanels(panels);
        return true;
    }
    void updatePanels(List<Panel> panels) {
        if (mReleased || mPanels.equals(panels)) return;
        mPanels = List.copyOf(panels);
        apply(currentActivity());
        if (mBoundsListener != null) mBoundsListener.onBoundsChanged(boundsList());
    }
    List<Rect> boundsList() { return mPanels.stream().map(p -> new Rect(p.content())).toList(); }
    List<Panel> panels() { return mPanels; }
    Rect appliedBounds() { return mPanels.isEmpty() ? new Rect() : new Rect(mPanels.get(0).content()); }
    boolean contains(float x, float y, boolean edgeHidden, int edgeHeight) {
        for (var panel : mPanels) {
            ShellBounds bounds = PanelGeometry.presented(bounds(panel.output()), bounds(edgeHidden ? panel.paint() : panel.frame()),
                    panel.edge(), true, edgeHidden, edgeHeight);
            if (x >= bounds.left() && x < bounds.right() && y >= bounds.top() && y < bounds.bottom()) return true;
        }
        return false;
    }
    private static ShellBounds bounds(Rect r) { return new ShellBounds(r.left, r.top, r.right, r.bottom); }
    void setPresented(boolean presented) {
        if (mReleased || mPresented == presented) return;
        mPresented = presented; apply(currentActivity());
    }
    void setEdgeHidden(boolean hidden, int edgeHeight) {
        if (mReleased) return;
        int height = Math.max(1, edgeHeight);
        if (mEdgeHidden == hidden && mEdgeHeight == height) return;
        mEdgeHidden = hidden; mEdgeHeight = height; apply(currentActivity());
    }
    void setEdgeInputListener(EdgeInputListener listener) { if (!mReleased) mEdgeInputListener = listener; }
    void release() {
        if (mReleased) return;
        mReleased = true;
        DesktopChromeActivity activity = null;
        synchronized (REGISTRY_LOCK) {
            if (HOSTS.get(mDisplayId) == this) { HOSTS.remove(mDisplayId); activity = ACTIVITIES.get(mDisplayId); }
        }
        if (activity != null) activity.detachPanels();
        mPanels = List.of(); mEdgeInputListener = null;
    }
    static void registerActivity(int displayId, DesktopChromeActivity activity) {
        if (displayId == Display.INVALID_DISPLAY || activity == null) return;
        final DesktopTaskbarHost host;
        synchronized (REGISTRY_LOCK) { ACTIVITIES.put(displayId, activity); host = HOSTS.get(displayId); }
        if (host != null) host.apply(activity);
    }
    static void unregisterActivity(int displayId, DesktopChromeActivity activity) {
        synchronized (REGISTRY_LOCK) { if (ACTIVITIES.get(displayId) == activity) ACTIVITIES.remove(displayId); }
    }
    static void dispatchEdgeInput(int displayId, MotionEvent event) {
        final DesktopTaskbarHost host;
        synchronized (REGISTRY_LOCK) { host = HOSTS.get(displayId); }
        if (host != null && !host.mReleased && host.mEdgeInputListener != null && event != null) host.mEdgeInputListener.onEdgeInput(event);
    }
    private DesktopChromeActivity currentActivity() {
        synchronized (REGISTRY_LOCK) { return HOSTS.get(mDisplayId) == this ? ACTIVITIES.get(mDisplayId) : null; }
    }
    private void apply(DesktopChromeActivity activity) {
        if (activity == null || activity != currentActivity() || mReleased) return;
        activity.attachPanels(mPanels);
        activity.setPresentation(mPresented, mEdgeHidden, mEdgeHeight);
    }
}
