package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

final class OverlayPanelController {
    private static final String TAG = "MagicDeskPanels";

    interface ChildInputListener {
        void onSecondaryClick(float x, float y);
    }

    private final Context mApplicationContext;
    private final AppOpsManager mAppOpsManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Context mWindowContext;
    private final WindowManager mWindowManager;
    private final Rect mBounds = new Rect();
    private final Rect mChildBounds = new Rect();
    private final Rect mPersistentBounds = new Rect();

    private View mVisiblePanel;
    private String mVisibleTitle = "";
    private View mChildPanel;
    private FrameLayout mChildHost;
    private String mChildTitle = "";
    private View mPersistentView;
    private WindowManager.LayoutParams mPersistentParams;
    private View mTransientView;
    private View mSurfaceTraversalFence;
    private boolean mAdded;
    private boolean mChildAdded;
    private boolean mVisibleFocusable;
    private boolean mChildFocusable;
    private boolean mPersistentAdded;
    private boolean mTransientAdded;
    private boolean mSurfaceTraversalFenceAdded;
    private boolean mOverlayPermissionGranted;
    private boolean mPermissionWatcherStarted;
    private boolean mReleased;
    private View mTextInputView;
    private InputConnection mTextInputConnection;
    private View mOwnerFocusBeforeChild;
    private final Runnable mTransientTimeout = this::hideTransient;
    private final AppOpsManager.OnOpChangedListener mOverlayPermissionListener;

    OverlayPanelController(final Context context, final int displayId) {
        mApplicationContext = context.getApplicationContext();
        mAppOpsManager = mApplicationContext.getSystemService(AppOpsManager.class);
        mOverlayPermissionGranted = Settings.canDrawOverlays(mApplicationContext);
        mOverlayPermissionListener = (operation, packageName) -> {
            if (!AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW.equals(operation)
                    || !mApplicationContext.getPackageName().equals(packageName)) {
                return;
            }
            mMainHandler.post(this::reconcileOverlayPermission);
        };
        final DisplayManager displayManager = mApplicationContext.getSystemService(
                DisplayManager.class);
        final Display display = displayManager == null
                ? null : displayManager.getDisplay(displayId);
        if (display == null) {
            Log.w(TAG, "display not found: " + displayId);
            CompatibilityDiagnostics.record(
                    "OVERLAY-002",
                    "Cannot create desktop overlays",
                    "Display not found: " + displayId);
            mWindowContext = null;
            mWindowManager = null;
            return;
        }
        final Context windowContext = mApplicationContext
                .createDisplayContext(display)
                .createWindowContext(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null);
        mWindowContext = windowContext;
        mWindowManager = windowContext.getSystemService(WindowManager.class);
        if (mAppOpsManager != null) {
            mAppOpsManager.startWatchingMode(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    mApplicationContext.getPackageName(),
                    mOverlayPermissionListener);
            mPermissionWatcherStarted = true;
        }
    }

    // The listener observes ACTION_OUTSIDE only and does not implement panel clicks.
    @SuppressLint("ClickableViewAccessibility")
    boolean show(final View panel, final int left, final int top,
            final int width, final int height, final boolean focusable,
            final String title) {
        return show(panel, left, top, width, height, focusable, true, title);
    }

    @SuppressLint("ClickableViewAccessibility")
    boolean show(final View panel, final int left, final int top,
            final int width, final int height, final boolean focusable,
            final boolean inputMethodTarget, final String title) {
        if (mReleased || panel == null || mWindowManager == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        hideAll();

        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!focusable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (!inputMethodTarget) {
            flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = left;
        params.y = top;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        params.setTitle(title);

        panel.setVisibility(View.VISIBLE);
        panel.setOnTouchListener((target, event) -> {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_OUTSIDE
                    && target == mVisiblePanel
                    && !mChildBounds.contains(
                            Math.round(event.getRawX()),
                            Math.round(event.getRawY()))
                    && !mPersistentBounds.contains(
                            Math.round(event.getRawX()), Math.round(event.getRawY()))) {
                hideAll();
            }
            return false;
        });
        try {
            mWindowManager.addView(panel, params);
            mVisiblePanel = panel;
            mVisibleTitle = title == null ? "" : title;
            mAdded = true;
            mVisibleFocusable = focusable;
            mBounds.set(left, top, left + width, top + height);
            recordPanelState(true);
            panel.postOnAnimation(() -> {
                if (!mAdded || mVisiblePanel != panel) {
                    return;
                }
                panel.invalidate();
                try {
                    mWindowManager.updateViewLayout(panel, params);
                } catch (RuntimeException e) {
                    Log.w(TAG, "failed to request panel frame " + title, e);
                    CompatibilityDiagnostics.record(
                            "OVERLAY-003",
                            "A desktop panel could not be redrawn",
                            "panel=" + title,
                            e);
                }
            });
            return true;
        } catch (RuntimeException e) {
            panel.setVisibility(View.GONE);
            mVisiblePanel = null;
            mVisibleTitle = "";
            mAdded = false;
            mVisibleFocusable = false;
            mBounds.setEmpty();
            Log.w(TAG, "failed to show panel " + title, e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-004",
                    "A desktop panel could not be shown",
                    "panel=" + title,
                    e);
            return false;
        }
    }

    /** Shows one modal child while retaining its owning desktop panel. */
    @SuppressLint("ClickableViewAccessibility")
    boolean showChild(final View panel, final int left, final int top,
            final int width, final int height, final String title,
            final ChildInputListener inputListener) {
        if (!mAdded || mVisiblePanel == null) {
            return show(panel, left, top, width, height, false, title);
        }
        if (mReleased || panel == null || mWindowManager == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        final View ownerFocus = mChildAdded
                ? mOwnerFocusBeforeChild : mVisiblePanel.findFocus();
        hideChild(false);
        mOwnerFocusBeforeChild = ownerFocus;
        if (mVisibleFocusable) {
            clearTextInputConnection();
        }

        final Rect hostBounds = new Rect(mBounds);
        hostBounds.union(left, top, left + width, top + height);
        final Rect menuBounds = new Rect(
                left - hostBounds.left,
                top - hostBounds.top,
                left - hostBounds.left + width,
                top - hostBounds.top + height);
        final FrameLayout host = new ChildPanelHost(
                panel.getContext(), menuBounds, inputListener);
        final FrameLayout.LayoutParams panelParams =
                new FrameLayout.LayoutParams(width, height);
        panelParams.leftMargin = menuBounds.left;
        panelParams.topMargin = menuBounds.top;
        host.addView(panel, panelParams);

        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!mVisibleFocusable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                hostBounds.width(),
                hostBounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = hostBounds.left;
        params.y = hostBounds.top;
        params.setTitle(title);

        panel.setVisibility(View.VISIBLE);
        host.setOnTouchListener((target, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE
                    || target != mChildHost) {
                return false;
            }
            final int x = Math.round(event.getRawX());
            final int y = Math.round(event.getRawY());
            if (mPersistentBounds.contains(x, y)) {
                hideChild();
            } else {
                hideAll();
            }
            return false;
        });
        try {
            mWindowManager.addView(host, params);
            mChildPanel = panel;
            mChildHost = host;
            mChildTitle = title == null ? "" : title;
            mChildAdded = true;
            mChildFocusable = mVisibleFocusable;
            mChildBounds.set(left, top, left + width, top + height);
            recordPanelState(true, mChildTitle, mChildBounds);
            host.postOnAnimation(() -> {
                if (!mChildAdded || mChildHost != host) {
                    return;
                }
                panel.invalidate();
                try {
                    mWindowManager.updateViewLayout(host, params);
                } catch (RuntimeException e) {
                    Log.w(TAG, "failed to request child panel frame " + title, e);
                    CompatibilityDiagnostics.record(
                            "OVERLAY-009",
                            "A desktop child panel could not be redrawn",
                            "panel=" + title,
                            e);
                }
            });
            return true;
        } catch (RuntimeException e) {
            host.removeView(panel);
            panel.setVisibility(View.GONE);
            mChildPanel = null;
            mChildHost = null;
            mChildTitle = "";
            mChildAdded = false;
            mChildFocusable = false;
            mOwnerFocusBeforeChild = null;
            mChildBounds.setEmpty();
            restoreOwnerFocus(ownerFocus);
            Log.w(TAG, "failed to show child panel " + title, e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-010",
                    "A desktop child panel could not be shown",
                    "panel=" + title,
                    e);
            return false;
        }
    }

    boolean attachPersistent(final View view, final int left, final int top,
            final int width, final int height,
            final boolean watchOutsideTouches,
            final String title) {
        if (mReleased || view == null || mWindowManager == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        detachPersistent();

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (watchOutsideTouches) {
            flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        }
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = left;
        params.y = top;
        params.setTitle(title);

        view.setVisibility(View.VISIBLE);
        try {
            mWindowManager.addView(view, params);
            mPersistentView = view;
            mPersistentParams = params;
            mPersistentAdded = true;
            mPersistentBounds.set(left, top, left + width, top + height);
            return true;
        } catch (RuntimeException e) {
            view.setVisibility(View.GONE);
            mPersistentView = null;
            mPersistentParams = null;
            mPersistentAdded = false;
            mPersistentBounds.setEmpty();
            Log.w(TAG, "failed to attach persistent overlay " + title, e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-005",
                    "The MagicDesk taskbar could not be attached",
                    "overlay=" + title,
                    e);
            return false;
        }
    }

    boolean showTransient(final View view, final int left, final int top,
            final int width, final int height, final long durationMillis,
            final String title) {
        if (mReleased || view == null || mWindowManager == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        hideTransient();

        final int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = left;
        params.y = top;
        params.setTitle(title);

        view.setVisibility(View.VISIBLE);
        try {
            mWindowManager.addView(view, params);
            mTransientView = view;
            mTransientAdded = true;
            if (durationMillis > 0) {
                mMainHandler.postDelayed(mTransientTimeout, durationMillis);
            }
            return true;
        } catch (RuntimeException e) {
            view.setVisibility(View.GONE);
            mTransientView = null;
            mTransientAdded = false;
            Log.w(TAG, "failed to show transient overlay " + title, e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-006",
                    "A transient desktop message could not be shown",
                    "overlay=" + title,
                    e);
            return false;
        }
    }

    void hideTransient() {
        mMainHandler.removeCallbacks(mTransientTimeout);
        final View view = mTransientView;
        if (mTransientAdded && view != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(view);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove transient overlay", e);
            }
        }
        if (view != null) {
            view.setVisibility(View.GONE);
        }
        mTransientView = null;
        mTransientAdded = false;
    }

    boolean runAfterSurfaceTraversalFence(final Runnable action) {
        if (mReleased || mWindowContext == null || mWindowManager == null
                || action == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        removeSurfaceTraversalFence();

        final View fence = new View(mWindowContext);
        fence.setBackgroundColor(Color.TRANSPARENT);
        final int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("MagicDesk task activation fence");
        try {
            mWindowManager.addView(fence, params);
            mSurfaceTraversalFence = fence;
            mSurfaceTraversalFenceAdded = true;
            fence.postOnAnimation(() -> {
                if (mSurfaceTraversalFenceAdded
                        && mSurfaceTraversalFence == fence) {
                    action.run();
                }
            });
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "failed to add task activation surface fence", error);
            mSurfaceTraversalFence = null;
            mSurfaceTraversalFenceAdded = false;
            return false;
        }
    }

    private void removeSurfaceTraversalFence() {
        final View fence = mSurfaceTraversalFence;
        if (mSurfaceTraversalFenceAdded && fence != null
                && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(fence);
            } catch (RuntimeException error) {
                Log.w(TAG, "failed to remove task activation surface fence",
                        error);
            }
        }
        mSurfaceTraversalFence = null;
        mSurfaceTraversalFenceAdded = false;
    }

    void setPersistentVisible(final boolean visible) {
        if (mPersistentAdded && mPersistentView != null) {
            mPersistentView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    void updatePersistentBounds(
            final int left,
            final int top,
            final int width,
            final int height) {
        if (!mPersistentAdded || mPersistentView == null
                || mWindowManager == null) {
            return;
        }
        final WindowManager.LayoutParams params = mPersistentParams;
        if (params == null) {
            return;
        }
        params.x = left;
        params.y = top;
        params.width = width;
        params.height = height;
        try {
            mWindowManager.updateViewLayout(mPersistentView, params);
            mPersistentBounds.set(
                    left, top, left + width, top + height);
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to update persistent overlay bounds", e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-007",
                    "The MagicDesk taskbar could not be repositioned",
                    "bounds=" + left + "," + top + " "
                            + width + "x" + height,
                    e);
        }
    }

    void hide(final View panel) {
        if (panel != null && panel == mChildPanel) {
            hideChild();
        } else if (panel != null && panel == mVisiblePanel) {
            hideAll();
        }
    }

    void hideTop() {
        if (mChildAdded && mChildPanel != null) {
            hideChild();
        } else {
            hideAll();
        }
    }

    void hideAll() {
        hideChild(false);
        hideTransient();
        removeSurfaceTraversalFence();
        clearTextInputConnection();
        final View panel = mVisiblePanel;
        if (mAdded && panel != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(panel);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove panel", e);
            }
        }
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        final boolean wasVisible = mAdded && mVisiblePanel != null;
        if (wasVisible) {
            recordPanelState(false);
        }
        mVisiblePanel = null;
        mVisibleTitle = "";
        mAdded = false;
        mVisibleFocusable = false;
        mBounds.setEmpty();
    }

    private void hideChild() {
        hideChild(true);
    }

    private void hideChild(final boolean restoreOwnerFocus) {
        final View panel = mChildPanel;
        final FrameLayout host = mChildHost;
        final View ownerFocus = mOwnerFocusBeforeChild;
        final boolean wasFocusable = mChildFocusable;
        if (mChildAdded && host != null && mWindowManager != null) {
            recordPanelState(false, mChildTitle, mChildBounds);
            try {
                mWindowManager.removeViewImmediate(host);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove child panel", e);
            }
        }
        if (host != null && panel != null && panel.getParent() == host) {
            host.removeView(panel);
        }
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        mChildPanel = null;
        mChildHost = null;
        mChildTitle = "";
        mChildAdded = false;
        mChildFocusable = false;
        mOwnerFocusBeforeChild = null;
        mChildBounds.setEmpty();
        if (restoreOwnerFocus && wasFocusable) {
            restoreOwnerFocus(ownerFocus);
        }
    }

    private void restoreOwnerFocus(final View ownerFocus) {
        if (!mAdded || !mVisibleFocusable || mVisiblePanel == null) {
            return;
        }
        final View target = ownerFocus == null ? mVisiblePanel : ownerFocus;
        target.post(() -> {
            if (mAdded && !mChildAdded && target.isAttachedToWindow()) {
                target.requestFocusFromTouch();
            }
        });
    }

    void release() {
        mReleased = true;
        if (mPermissionWatcherStarted && mAppOpsManager != null) {
            mAppOpsManager.stopWatchingMode(mOverlayPermissionListener);
            mPermissionWatcherStarted = false;
        }
        hideAll();
        detachPersistent();
    }

    private void reconcileOverlayPermission() {
        if (mReleased) {
            return;
        }
        final boolean granted = Settings.canDrawOverlays(mApplicationContext);
        if (granted == mOverlayPermissionGranted) {
            return;
        }
        mOverlayPermissionGranted = granted;
        if (!granted) {
            hideAll();
            suspendPersistent();
            Log.i(TAG, "desktop overlays suspended after permission revocation");
            return;
        }
        resumePersistent();
    }

    private void suspendPersistent() {
        final View view = mPersistentView;
        if (!mPersistentAdded || view == null || mWindowManager == null) {
            return;
        }
        try {
            mWindowManager.removeViewImmediate(view);
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to suspend persistent overlay", e);
        }
        mPersistentAdded = false;
    }

    private void resumePersistent() {
        final View view = mPersistentView;
        if (mPersistentAdded || view == null || mPersistentParams == null
                || mWindowManager == null) {
            return;
        }
        try {
            mWindowManager.addView(view, mPersistentParams);
            mPersistentAdded = true;
            Log.i(TAG, "desktop overlays resumed after permission grant");
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to resume persistent overlay", e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-008",
                    "The MagicDesk taskbar could not be restored",
                    "overlay permission was granted again",
                    e);
        }
    }

    private void detachPersistent() {
        final View view = mPersistentView;
        if (mPersistentAdded && view != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(view);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove persistent overlay", e);
            }
        }
        if (view != null) {
            view.setVisibility(View.GONE);
        }
        mPersistentView = null;
        mPersistentParams = null;
        mPersistentAdded = false;
        mPersistentBounds.setEmpty();
    }

    boolean hasVisiblePanel() {
        return (mChildAdded && mChildPanel != null)
                || (mAdded && mVisiblePanel != null);
    }

    Rect visibleBounds() {
        return new Rect(mChildAdded ? mChildBounds : mBounds);
    }

    String visibleTitle() {
        return mChildAdded ? mChildTitle : mVisibleTitle;
    }

    boolean isVisible(final View panel) {
        return panel != null
                && ((panel == mChildPanel && mChildAdded)
                        || (panel == mVisiblePanel && mAdded));
    }

    boolean hasTextInputTarget() {
        final View inputPanel = topInputPanel();
        if (Looper.myLooper() != Looper.getMainLooper()
                || inputPanel == null) {
            return false;
        }
        final View focused = inputPanel.findFocus();
        return focused != null && focused.onCheckIsTextEditor();
    }

    boolean dispatchTextInput(
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        final View inputPanel = topInputPanel();
        if (Looper.myLooper() != Looper.getMainLooper()
                || inputPanel == null) {
            return false;
        }
        final View focused = inputPanel.findFocus();
        if (focused == null || !focused.onCheckIsTextEditor()) {
            clearTextInputConnection();
            return false;
        }
        final InputConnection connection = textInputConnection(focused);
        if (connection == null) {
            return false;
        }
        switch (action) {
            case PlatformTextInputDriver.COMMIT_TEXT:
                return connection.commitText(safeText(text), arg1);
            case PlatformTextInputDriver.SEND_KEY:
                final long eventTime = android.os.SystemClock.uptimeMillis();
                return connection.sendKeyEvent(new KeyEvent(
                        eventTime,
                        eventTime,
                        arg1,
                        arg2,
                        0,
                        arg3));
            case PlatformTextInputDriver.SET_COMPOSING_TEXT:
                return connection.setComposingText(safeText(text), arg1);
            case PlatformTextInputDriver.SET_COMPOSING_REGION:
                return connection.setComposingRegion(arg1, arg2);
            case PlatformTextInputDriver.FINISH_COMPOSING:
                return connection.finishComposingText();
            case PlatformTextInputDriver.DELETE_SURROUNDING:
                return connection.deleteSurroundingText(arg1, arg2);
            default:
                return false;
        }
    }

    private View topInputPanel() {
        if (mChildAdded && mChildPanel != null) {
            return mChildPanel;
        }
        return mAdded ? mVisiblePanel : null;
    }

    boolean contains(final float x, final float y) {
        final int roundedX = Math.round(x);
        final int roundedY = Math.round(y);
        return (mChildAdded && mChildBounds.contains(roundedX, roundedY))
                || (mAdded && mBounds.contains(roundedX, roundedY));
    }

    boolean containsVisiblePanelView(final View view) {
        return isDescendantOf(view, mChildAdded ? mChildPanel : null)
                || isDescendantOf(view, mAdded ? mVisiblePanel : null);
    }

    boolean isTopPanelFocusable() {
        return mChildAdded ? mChildFocusable : mVisibleFocusable;
    }

    private final class ChildPanelHost extends FrameLayout {
        private final Rect mMenuBounds;
        private final ChildInputListener mInputListener;
        private boolean mOutsideSecondaryDown;
        private float mSecondaryX;
        private float mSecondaryY;

        ChildPanelHost(
                final Context context,
                final Rect menuBounds,
                final ChildInputListener inputListener) {
            super(context);
            mMenuBounds = new Rect(menuBounds);
            mInputListener = inputListener;
        }

        @Override
        public boolean dispatchTouchEvent(final MotionEvent event) {
            if (handleOutsideEvent(event)) {
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(final MotionEvent event) {
            if (handleOutsideEvent(event)) {
                return true;
            }
            return super.dispatchGenericMotionEvent(event);
        }

        private boolean handleOutsideEvent(final MotionEvent event) {
            if (mMenuBounds.contains(
                    Math.round(event.getX()), Math.round(event.getY()))) {
                return false;
            }
            final int action = event.getActionMasked();
            final boolean secondary = (event.getButtonState()
                    & MotionEvent.BUTTON_SECONDARY) != 0
                    || event.getActionButton() == MotionEvent.BUTTON_SECONDARY;
            if ((action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_BUTTON_PRESS)
                    && secondary) {
                mOutsideSecondaryDown = true;
                mSecondaryX = event.getRawX();
                mSecondaryY = event.getRawY();
                return true;
            }
            if (mOutsideSecondaryDown
                    && (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_BUTTON_RELEASE)) {
                mOutsideSecondaryDown = false;
                final float x = mSecondaryX;
                final float y = mSecondaryY;
                post(() -> {
                    hideChild(false);
                    if (mInputListener != null) {
                        mInputListener.onSecondaryClick(x, y);
                    }
                });
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                mOutsideSecondaryDown = false;
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                post(OverlayPanelController.this::hideChild);
                return true;
            }
            return mOutsideSecondaryDown;
        }
    }

    private static boolean isDescendantOf(
            final View view,
            final View ancestor) {
        View current = view;
        while (current != null && ancestor != null) {
            if (current == ancestor) {
                return true;
            }
            final ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private InputConnection textInputConnection(final View focused) {
        if (mTextInputView == focused && mTextInputConnection != null) {
            return mTextInputConnection;
        }
        clearTextInputConnection();
        final InputConnection connection = focused.onCreateInputConnection(
                new EditorInfo());
        if (connection != null) {
            mTextInputView = focused;
            mTextInputConnection = connection;
        }
        return connection;
    }

    private void clearTextInputConnection() {
        if (mTextInputConnection != null) {
            mTextInputConnection.closeConnection();
        }
        mTextInputView = null;
        mTextInputConnection = null;
    }

    private static String safeText(final String text) {
        return text == null ? "" : text;
    }

    private void recordPanelState(final boolean visible) {
        recordPanelState(visible, mVisibleTitle, mBounds);
    }

    private void recordPanelState(
            final boolean visible,
            final String title,
            final Rect bounds) {
        try {
            DesktopAutomationEventJournal.record(
                    "ui",
                    visible ? "popup_shown" : "popup_hidden",
                    true,
                    title,
                    new org.json.JSONObject()
                            .put("visible", visible)
                            .put("title", title)
                            .put("bounds", new org.json.JSONObject()
                                    .put("left", bounds.left)
                                    .put("top", bounds.top)
                                    .put("right", bounds.right)
                                    .put("bottom", bounds.bottom)));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "ui", visible ? "popup_shown" : "popup_hidden",
                    true, title);
        }
    }
}
