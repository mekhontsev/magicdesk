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
        boolean taskbarAutoHide();
        void onImeVisibilityChanged(boolean visible);
        void onViewportChanged();
        void onWorkAreaChanged();
    }

    private final Activity mActivity;
    private final RuntimeState mRuntimeState;

    private DesktopViewport mViewport;
    private final DesktopShellLayout mShellLayout = new DesktopShellLayout();
    private final Runnable mShellChanged = this::applyShellGeometry;
    private ShellLayout.Snapshot mAppliedLayout;
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
        updateShellLayout();
        mAppliedLayout = mShellLayout.snapshot();
        mShellLayout.listen(mShellChanged);
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

    DesktopShellLayout shellLayout() { return mShellLayout; }

    Rect taskbarBounds() {
        final ShellLayout.Surface taskbar = mShellLayout.taskbar();
        return taskbar == null ? new Rect() : rect(taskbar.content());
    }

    private Rect taskbarSurfaceBounds() {
        return rect(mShellLayout.taskbar().paint());
    }

    Rect workAreaBounds() {
        return rect(mShellLayout.snapshot().workArea());
    }

    Rect panelAreaBounds() {
        return rect(mShellLayout.snapshot().panelArea());
    }

    void refreshShellLayout() {
        updateShellLayout();
        applyViewportPadding();
        updateTaskbarBounds();
    }

    private void updateShellLayout() {
        mShellLayout.update(mViewport, mRuntimeState.taskbarHeight(), mRuntimeState.taskbarAutoHide());
    }

    private static Rect rect(final ShellBounds bounds) {
        return new Rect(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
    }

    void release() {
        mShellLayout.unlisten(mShellChanged);
        if (mWindowRoot != null) {
            mWindowRoot.setOnApplyWindowInsetsListener(null);
        }
        mWindowRoot = null;
        mDesktopContent = null;
        mStatusBarBackdrop = null;
        mNavigationBarBackdrop = null;
        mTaskbar = null;
        mTaskbarHost = null;
        mShellLayout.release();
    }

    private void applyShellGeometry() {
        final ShellLayout.Snapshot previous = mAppliedLayout;
        final ShellLayout.Snapshot next = mShellLayout.snapshot();
        mAppliedLayout = next;
        if (previous == next) return;
        if (previous == null || !previous.panelArea().equals(next.panelArea())) applyViewportPadding();
        updateTaskbarBounds();
        if (previous != null && !previous.workArea().equals(next.workArea())) mRuntimeState.onWorkAreaChanged();
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
        updateShellLayout();
        applyViewportPadding();
        updateSystemBarBackdrops();
        updateTaskbarBounds();
        mRuntimeState.onViewportChanged();
    }

    private void applyViewportPadding() {
        if (mDesktopContent == null || mViewport == null) {
            return;
        }
        final ShellLayout.Snapshot layout = mShellLayout.snapshot();
        final ShellBounds area = layout.panelArea();
        final ShellBounds output = layout.output();
        mDesktopContent.setPadding(area.left() - output.left(), area.top() - output.top(),
                output.right() - area.right(), output.bottom() - area.bottom());
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
