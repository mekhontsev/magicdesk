package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowMetrics;

/**
 * Owns desktop viewport policy and keeps the taskbar plane aligned with the
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
    private View mTaskbar;
    private DesktopTaskbarHost mTaskbarHost;

    DesktopLayoutController(
            final Activity activity,
            final RuntimeState runtimeState) {
        mActivity = activity;
        mRuntimeState = runtimeState;
        mViewport = readViewport();
    }

    void attachDesktopViews(
            final View windowRoot,
            final View desktopContent,
            final View statusBarBackdrop) {
        mWindowRoot = windowRoot;
        mDesktopContent = desktopContent;
        mStatusBarBackdrop = statusBarBackdrop;
        applyViewportPadding();
        updateStatusBarBackdrop();
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
        final Rect bounds = taskbarBounds();
        return taskbarHost.attachTaskbar(taskbar, bounds);
    }

    DesktopViewport viewport() {
        return mViewport;
    }

    Rect taskbarBounds() {
        return mViewport.taskbarBounds(mRuntimeState.taskbarHeight());
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

    void onWindowFocusChanged(final boolean hasFocus) {
        if (hasFocus) {
            applyPhoneSystemBarPolicy();
        }
    }

    void onWindowAttached() {
        applyPhoneSystemBarPolicy();
    }

    void release() {
        if (mWindowRoot != null) {
            mWindowRoot.setOnApplyWindowInsetsListener(null);
        }
        mWindowRoot = null;
        mDesktopContent = null;
        mStatusBarBackdrop = null;
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
        updateStatusBarBackdrop();
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
        final Rect bounds = taskbarBounds();
        mTaskbarHost.updateBounds(bounds);
    }

    private void updateStatusBarBackdrop() {
        if (mStatusBarBackdrop == null || mViewport == null) {
            return;
        }
        final int height = mRuntimeState.displayId() == Display.DEFAULT_DISPLAY
                ? mViewport.insetTop() : 0;
        final ViewGroup.LayoutParams layoutParams =
                mStatusBarBackdrop.getLayoutParams();
        if (layoutParams != null && layoutParams.height != height) {
            layoutParams.height = height;
            mStatusBarBackdrop.setLayoutParams(layoutParams);
        }
        mStatusBarBackdrop.setVisibility(height > 0 ? View.VISIBLE : View.GONE);
    }

    private void applyPhoneSystemBarPolicy() {
        if (mRuntimeState.displayId() != Display.DEFAULT_DISPLAY) {
            return;
        }
        mActivity.getWindow().setDecorFitsSystemWindows(false);
        final WindowInsetsController controller =
                mActivity.getWindow().getInsetsController();
        if (controller == null) {
            return;
        }
        controller.hide(WindowInsets.Type.navigationBars());
        controller.setSystemBarsBehavior(
                WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
