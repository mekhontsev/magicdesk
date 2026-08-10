package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowMetrics;

/**
 * Owns desktop viewport policy and keeps the persistent taskbar aligned with
 * the current display geometry.
 */
final class DesktopLayoutController {
    private static final String TAG = "MagicDeskLayout";

    interface RuntimeState {
        int displayId();
        int taskbarHeight();
        void onViewportChanged();
    }

    private final Activity mActivity;
    private final RuntimeState mRuntimeState;

    private DesktopViewport mViewport;
    private View mDesktopRoot;
    private View mTaskbar;
    private OverlayPanelController mOverlays;
    private int mTaskbarBottomInset;

    DesktopLayoutController(
            final Activity activity,
            final RuntimeState runtimeState) {
        mActivity = activity;
        mRuntimeState = runtimeState;
        mViewport = readViewport();
    }

    void attachDesktopRoot(final View root) {
        mDesktopRoot = root;
        applyViewportPadding();
        if (root == null) {
            return;
        }
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            final WindowMetrics metrics =
                    mActivity.getWindowManager().getCurrentWindowMetrics();
            applyViewport(mRuntimeState.displayId() == Display.DEFAULT_DISPLAY
                    ? DesktopViewport.fromWindowMetrics(metrics, windowInsets)
                    : DesktopViewport.fromDisplayBounds(metrics.getBounds()));
            return windowInsets;
        });
    }

    boolean attachTaskbar(
            final View taskbar,
            final OverlayPanelController overlays,
            final String title) {
        mTaskbar = taskbar;
        mOverlays = overlays;
        if (taskbar == null || overlays == null) {
            return false;
        }
        final Rect bounds = taskbarBounds();
        return overlays.attachPersistent(
                taskbar,
                bounds.left,
                bounds.top,
                bounds.width(),
                bounds.height(),
                title);
    }

    DesktopViewport viewport() {
        return mViewport;
    }

    Rect taskbarBounds() {
        return mViewport.taskbarBounds(
                mRuntimeState.taskbarHeight(),
                mTaskbarBottomInset);
    }

    void setTaskbarBottomInset(final int bottomInset) {
        final int normalized = Math.max(0, bottomInset);
        if (mTaskbarBottomInset == normalized) {
            return;
        }
        mTaskbarBottomInset = normalized;
        updateTaskbarBounds();
    }

    int desktopAreaWidth() {
        return mViewport.contentWidth();
    }

    int desktopAreaHeight() {
        return mViewport.contentHeight();
    }

    int desktopAreaLeft() {
        return mViewport.contentLeft();
    }

    int desktopAreaTop() {
        return mViewport.contentTop();
    }

    void release() {
        if (mDesktopRoot != null) {
            mDesktopRoot.setOnApplyWindowInsetsListener(null);
        }
        mDesktopRoot = null;
        mTaskbar = null;
        mOverlays = null;
    }

    private DesktopViewport readViewport() {
        try {
            final WindowMetrics metrics =
                    mActivity.getWindowManager().getCurrentWindowMetrics();
            return mRuntimeState.displayId() == Display.DEFAULT_DISPLAY
                    ? DesktopViewport.fromWindowMetrics(metrics)
                    : DesktopViewport.fromDisplayBounds(metrics.getBounds());
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to read desktop viewport", e);
            final int width = Math.max(
                    1, mActivity.getResources().getDisplayMetrics().widthPixels);
            final int height = Math.max(
                    1, mActivity.getResources().getDisplayMetrics().heightPixels);
            return new DesktopViewport(
                    new Rect(0, 0, width, height), 0, 0, 0, 0);
        }
    }

    private void applyViewport(final DesktopViewport viewport) {
        if (viewport == null || viewport.equals(mViewport)) {
            return;
        }
        mViewport = viewport;
        applyViewportPadding();
        updateTaskbarBounds();
        mRuntimeState.onViewportChanged();
    }

    private void applyViewportPadding() {
        if (mDesktopRoot == null || mViewport == null) {
            return;
        }
        mDesktopRoot.setPadding(
                mViewport.insetLeft(),
                mViewport.insetTop(),
                mViewport.insetRight(),
                mViewport.insetBottom());
    }

    private void updateTaskbarBounds() {
        if (mTaskbar == null || mOverlays == null || mViewport == null) {
            return;
        }
        final Rect bounds = taskbarBounds();
        mOverlays.updatePersistentBounds(
                bounds.left,
                bounds.top,
                bounds.width(),
                bounds.height());
    }
}
