package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
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

    private final Context mApplicationContext;
    private final AppOpsManager mAppOpsManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
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
    private boolean mAdded;
    private boolean mChildAdded;
    private boolean mPersistentAdded;
    private boolean mTransientAdded;
    private boolean mOverlayPermissionGranted;
    private boolean mPermissionWatcherStarted;
    private boolean mReleased;
    private View mTextInputView;
    private InputConnection mTextInputConnection;
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
            mWindowManager = null;
            return;
        }
        final Context windowContext = mApplicationContext
                .createDisplayContext(display)
                .createWindowContext(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null);
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
            final int width, final int height, final String title) {
        if (!mAdded || mVisiblePanel == null) {
            return show(panel, left, top, width, height, false, title);
        }
        if (mReleased || panel == null || mWindowManager == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        hideChild();

        final Rect hostBounds = new Rect(mBounds);
        hostBounds.union(left, top, left + width, top + height);
        final Rect menuBounds = new Rect(
                left - hostBounds.left,
                top - hostBounds.top,
                left - hostBounds.left + width,
                top - hostBounds.top + height);
        final FrameLayout host = new FrameLayout(panel.getContext()) {
            @Override
            public boolean dispatchTouchEvent(final MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && !menuBounds.contains(
                                Math.round(event.getX()),
                                Math.round(event.getY()))) {
                    post(OverlayPanelController.this::hideChild);
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        final FrameLayout.LayoutParams panelParams =
                new FrameLayout.LayoutParams(width, height);
        panelParams.leftMargin = menuBounds.left;
        panelParams.topMargin = menuBounds.top;
        host.addView(panel, panelParams);

        final int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
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
            mChildBounds.setEmpty();
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
        hideChild();
        hideTransient();
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
        mBounds.setEmpty();
    }

    private void hideChild() {
        final View panel = mChildPanel;
        final FrameLayout host = mChildHost;
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
        mChildBounds.setEmpty();
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
        if (Looper.myLooper() != Looper.getMainLooper()
                || !mAdded
                || mVisiblePanel == null) {
            return false;
        }
        final View focused = mVisiblePanel.findFocus();
        return focused != null && focused.onCheckIsTextEditor();
    }

    boolean dispatchTextInput(
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        if (Looper.myLooper() != Looper.getMainLooper()
                || !mAdded
                || mVisiblePanel == null) {
            return false;
        }
        final View focused = mVisiblePanel.findFocus();
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

    boolean containsTopPanelView(final View view) {
        return isDescendantOf(
                view, mChildAdded ? mChildPanel : mVisiblePanel);
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
