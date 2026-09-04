package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.FrameLayout;

/**
 * Owns desktop viewport policy and keeps taskbar chrome aligned with the
 * current display geometry.
 */
final class DesktopLayoutController {
    private static final String TAG = "MagicDeskLayout";

    interface RuntimeState {
        int displayId();
        int taskbarHeight();
        void onImeVisibilityChanged(boolean visible);
        void onViewportChanged();
    }

    private final Activity mActivity;
    private final RuntimeState mRuntimeState;

    private DesktopViewport mViewport;
    private View mWindowRoot;
    private View mDesktopContent;
    private View mStatusBarBackdrop;
    private View mNavigationBarBackdrop;
    private View mTaskbar;
    private DesktopTaskbarHost mTaskbarHost;

    DesktopLayoutController(
            final Activity activity,
            final RuntimeState runtimeState) {
        mActivity = activity;
        mRuntimeState = runtimeState;
        if (runtimeState.displayId() == Display.DEFAULT_DISPLAY) {
            // Keep the HOME surface full-display so the explicit backdrop
            // views can cover stable system-bar insets without recropping the
            // wallpaper.
            activity.getWindow().setDecorFitsSystemWindows(false);
        }
        mViewport = readViewport();
    }

    void attachDesktopViews(
            final View windowRoot,
            final View desktopContent,
            final View statusBarBackdrop,
            final View navigationBarBackdrop) {
        mWindowRoot = windowRoot;
        mDesktopContent = desktopContent;
        mStatusBarBackdrop = statusBarBackdrop;
        mNavigationBarBackdrop = navigationBarBackdrop;
        applyViewportPadding();
        updateSystemBarBackdrops();
        if (windowRoot == null) {
            return;
        }
        windowRoot.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            final WindowMetrics metrics =
                    mActivity.getWindowManager().getCurrentWindowMetrics();
            final boolean imeVisible = windowInsets.isVisible(
                    WindowInsets.Type.ime());
            mRuntimeState.onImeVisibilityChanged(imeVisible);
            applyViewport(mRuntimeState.displayId() == Display.DEFAULT_DISPLAY
                    ? DesktopViewport.fromPhoneDesktopWindowMetrics(
                            metrics, windowInsets)
                    : DesktopViewport.fromDisplayBounds(metrics.getBounds()));
            return windowInsets;
        });
    }

    boolean attachTaskbar(
            final View taskbar,
            final DesktopTaskbarHost taskbarHost) {
        mTaskbar = taskbar;
        mTaskbarHost = taskbarHost;
        if (taskbar == null || taskbarHost == null) {
            return false;
        }
        return taskbarHost.attachTaskbar(
                taskbar,
                taskbarBounds(),
                taskbarSurfaceBounds());
    }

    DesktopViewport viewport() {
        return mViewport;
    }

    Rect taskbarBounds() {
        return mViewport.taskbarBounds(mRuntimeState.taskbarHeight());
    }

    private Rect taskbarSurfaceBounds() {
        return mViewport.taskbarSurfaceBounds(
                mRuntimeState.taskbarHeight());
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
        if (mWindowRoot != null) {
            mWindowRoot.setOnApplyWindowInsetsListener(null);
        }
        mWindowRoot = null;
        mDesktopContent = null;
        mStatusBarBackdrop = null;
        mNavigationBarBackdrop = null;
        mTaskbar = null;
        mTaskbarHost = null;
    }

    private DesktopViewport readViewport() {
        try {
            final WindowMetrics metrics =
                    mActivity.getWindowManager().getCurrentWindowMetrics();
            return mRuntimeState.displayId() == Display.DEFAULT_DISPLAY
                    ? DesktopViewport.fromPhoneDesktopWindowMetrics(metrics)
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
        updateSystemBarBackdrops();
        updateTaskbarBounds();
        mRuntimeState.onViewportChanged();
    }

    private void applyViewportPadding() {
        if (mDesktopContent == null || mViewport == null) {
            return;
        }
        mDesktopContent.setPadding(
                mViewport.insetLeft(),
                mViewport.insetTop(),
                mViewport.insetRight(),
                mViewport.insetBottom());
    }

    private void updateTaskbarBounds() {
        if (mTaskbar == null || mTaskbarHost == null || mViewport == null) {
            return;
        }
        mTaskbarHost.updateBounds(taskbarBounds(), taskbarSurfaceBounds());
    }

    private void updateSystemBarBackdrops() {
        if (mViewport == null) {
            return;
        }
        final boolean phone = mRuntimeState.displayId()
                == Display.DEFAULT_DISPLAY;
        updateHorizontalBackdrop(
                mStatusBarBackdrop,
                phone ? mViewport.insetTop() : 0,
                Gravity.TOP);
        updateHorizontalBackdrop(
                mNavigationBarBackdrop,
                phone ? mViewport.insetBottom() : 0,
                Gravity.BOTTOM);
    }

    private static void updateHorizontalBackdrop(
            final View backdrop,
            final int height,
            final int gravity) {
        if (backdrop == null) {
            return;
        }
        final ViewGroup.LayoutParams current = backdrop.getLayoutParams();
        if (!(current instanceof FrameLayout.LayoutParams)) {
            return;
        }
        final FrameLayout.LayoutParams layoutParams =
                (FrameLayout.LayoutParams) current;
        final int resolvedHeight = Math.max(0, height);
        if (layoutParams.height != resolvedHeight
                || layoutParams.gravity != gravity) {
            layoutParams.height = resolvedHeight;
            layoutParams.gravity = gravity;
            backdrop.setLayoutParams(layoutParams);
        }
        backdrop.setVisibility(
                resolvedHeight > 0 ? View.VISIBLE : View.GONE);
    }

}
