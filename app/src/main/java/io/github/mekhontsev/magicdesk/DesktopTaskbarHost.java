package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/** Connects desktop-owned taskbar UI to the shared desktop chrome task. */
final class DesktopTaskbarHost {
    interface BoundsListener {
        void onBoundsChanged(Rect bounds);
    }

    interface EdgeInputListener {
        void onEdgeInput(MotionEvent event);
    }

    private static final Object REGISTRY_LOCK = new Object();
    private static final Map<Integer, DesktopTaskbarHost> HOSTS =
            new HashMap<>();
    private static final Map<Integer, DesktopChromeActivity> ACTIVITIES =
            new HashMap<>();

    private final int mDisplayId;
    private final BoundsListener mBoundsListener;
    private final Rect mTaskbarBounds = new Rect();
    private final Rect mSurfaceBounds = new Rect();
    private final Rect mAppliedBounds = new Rect();

    private View mTaskbar;
    private boolean mPresented = true;
    private boolean mEdgeHidden;
    private int mEdgeHeight = 1;
    private EdgeInputListener mEdgeInputListener;
    private boolean mReleased;

    DesktopTaskbarHost(
            final int displayId,
            final BoundsListener boundsListener) {
        if (displayId == Display.INVALID_DISPLAY) {
            throw new IllegalArgumentException(
                    "desktop taskbar requires a display");
        }
        mDisplayId = displayId;
        mBoundsListener = boundsListener;
    }

    boolean attachTaskbar(
            final View taskbar,
            final Rect taskbarBounds,
            final Rect surfaceBounds) {
        if (mReleased || taskbar == null
                || taskbarBounds == null || taskbarBounds.isEmpty()
                || surfaceBounds == null || surfaceBounds.isEmpty()
                || !surfaceBounds.contains(taskbarBounds)) {
            return false;
        }
        mTaskbar = taskbar;
        mTaskbarBounds.set(taskbarBounds);
        mSurfaceBounds.set(surfaceBounds);
        final DesktopChromeActivity activity;
        synchronized (REGISTRY_LOCK) {
            HOSTS.put(Integer.valueOf(mDisplayId), this);
            activity = ACTIVITIES.get(Integer.valueOf(mDisplayId));
        }
        apply(activity);
        updateAppliedBounds();
        return true;
    }

    void updateBounds(
            final Rect taskbarBounds,
            final Rect surfaceBounds) {
        if (mReleased
                || taskbarBounds == null || taskbarBounds.isEmpty()
                || surfaceBounds == null || surfaceBounds.isEmpty()
                || !surfaceBounds.contains(taskbarBounds)
                || (mTaskbarBounds.equals(taskbarBounds)
                        && mSurfaceBounds.equals(surfaceBounds))) {
            return;
        }
        mTaskbarBounds.set(taskbarBounds);
        mSurfaceBounds.set(surfaceBounds);
        apply(currentActivity());
        updateAppliedBounds();
    }

    void setPresented(final boolean presented) {
        if (mReleased || mPresented == presented) {
            return;
        }
        mPresented = presented;
        apply(currentActivity());
    }

    void setEdgeHidden(final boolean hidden, final int edgeHeight) {
        if (mReleased) {
            return;
        }
        final int normalizedHeight = Math.max(1, edgeHeight);
        if (mEdgeHidden == hidden && mEdgeHeight == normalizedHeight) {
            return;
        }
        mEdgeHidden = hidden;
        mEdgeHeight = normalizedHeight;
        apply(currentActivity());
    }

    void setEdgeInputListener(final EdgeInputListener listener) {
        if (!mReleased) {
            mEdgeInputListener = listener;
        }
    }

    void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        final DesktopChromeActivity activity;
        synchronized (REGISTRY_LOCK) {
            if (HOSTS.get(Integer.valueOf(mDisplayId)) == this) {
                HOSTS.remove(Integer.valueOf(mDisplayId));
            }
            activity = ACTIVITIES.get(Integer.valueOf(mDisplayId));
        }
        if (activity != null) {
            activity.detachTaskbar();
        }
        mTaskbar = null;
        mEdgeInputListener = null;
        mTaskbarBounds.setEmpty();
        mSurfaceBounds.setEmpty();
        mAppliedBounds.setEmpty();
    }

    Rect appliedBounds() {
        return mAppliedBounds.isEmpty()
                ? new Rect(mTaskbarBounds) : new Rect(mAppliedBounds);
    }

    boolean runAfterSurfaceTraversalFence(final Runnable action) {
        final DesktopChromeActivity activity = currentActivity();
        return !mReleased && activity != null
                && activity.runAfterSurfaceTraversalFence(action);
    }

    static void registerActivity(
            final int displayId,
            final DesktopChromeActivity activity) {
        if (displayId == Display.INVALID_DISPLAY || activity == null) {
            return;
        }
        final DesktopTaskbarHost host;
        synchronized (REGISTRY_LOCK) {
            ACTIVITIES.put(Integer.valueOf(displayId), activity);
            host = HOSTS.get(Integer.valueOf(displayId));
        }
        if (host != null) {
            host.apply(activity);
        }
    }

    static void unregisterActivity(
            final int displayId,
            final DesktopChromeActivity activity) {
        synchronized (REGISTRY_LOCK) {
            if (ACTIVITIES.get(Integer.valueOf(displayId)) == activity) {
                ACTIVITIES.remove(Integer.valueOf(displayId));
            }
        }
    }

    static void dispatchEdgeInput(
            final int displayId,
            final MotionEvent event) {
        final DesktopTaskbarHost host;
        synchronized (REGISTRY_LOCK) {
            host = HOSTS.get(Integer.valueOf(displayId));
        }
        if (host != null) {
            host.onEdgeInput(event);
        }
    }

    static void notifyPanelLayoutCommitted(
            final int displayId,
            final DesktopChromeActivity activity) {
        final DesktopTaskbarHost host;
        synchronized (REGISTRY_LOCK) {
            if (ACTIVITIES.get(Integer.valueOf(displayId)) != activity) {
                return;
            }
            host = HOSTS.get(Integer.valueOf(displayId));
        }
        if (host != null) {
            host.onPanelLayoutCommitted();
        }
    }

    private DesktopChromeActivity currentActivity() {
        synchronized (REGISTRY_LOCK) {
            return ACTIVITIES.get(Integer.valueOf(mDisplayId));
        }
    }

    private void onEdgeInput(final MotionEvent event) {
        final EdgeInputListener listener = mEdgeInputListener;
        if (!mReleased && listener != null && event != null) {
            listener.onEdgeInput(event);
        }
    }

    private void onPanelLayoutCommitted() {
        if (!mReleased && mPresented) {
            MagicDeskRuntime.raiseDesktopChrome(mDisplayId);
        }
    }

    private void apply(final DesktopChromeActivity activity) {
        if (activity == null || mReleased || mTaskbar == null) {
            return;
        }
        activity.attachTaskbar(
                mTaskbar,
                mTaskbarBounds.height(),
                mSurfaceBounds.height());
        activity.setPresentation(mPresented, mEdgeHidden, mEdgeHeight);
    }

    private void updateAppliedBounds() {
        if (mReleased || mTaskbarBounds.isEmpty() || mSurfaceBounds.isEmpty()) {
            return;
        }
        if (!mAppliedBounds.equals(mTaskbarBounds)) {
            mAppliedBounds.set(mTaskbarBounds);
            if (mBoundsListener != null) {
                mBoundsListener.onBoundsChanged(
                        new Rect(mTaskbarBounds));
            }
        }
    }
}
