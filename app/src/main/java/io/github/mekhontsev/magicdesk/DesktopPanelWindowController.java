package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

import java.util.HashMap;
import java.util.Map;

/** Hosts desktop panels as child windows of the persistent desktop chrome task. */
final class DesktopPanelWindowController {
    private static final String TAG = "MagicDeskPanels";
    private static final Object REGISTRY_LOCK = new Object();
    private static final Map<Integer, DesktopPanelWindowController> CONTROLLERS =
            new HashMap<>();
    private static final Map<Integer, HostRegistration> HOSTS =
            new HashMap<>();

    interface ChildInputListener {
        void onSecondaryClick(float x, float y);
    }

    interface PanelVisibilityListener {
        void onPanelVisibilityChanged(View panel, boolean visible);
    }

    private final int mDisplayId;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final PanelVisibilityListener mPanelVisibilityListener;
    private final Rect mBounds = new Rect();
    private final Rect mChildBounds = new Rect();
    private final Rect mInteractionOwnerBounds = new Rect();

    private DesktopChromeActivity mHostActivity;
    private WindowManager mWindowManager;
    private IBinder mWindowToken;
    private boolean mHostLaunchRequested;
    private boolean mReleased;

    private View mVisiblePanel;
    private String mVisibleTitle = "";
    private WindowManager.LayoutParams mVisibleParams;
    private boolean mVisibleRequested;
    private boolean mVisibleAdded;
    private boolean mVisibleFocusable;

    private View mChildPanel;
    private FrameLayout mChildHost;
    private String mChildTitle = "";
    private WindowManager.LayoutParams mChildParams;
    private boolean mChildRequested;
    private boolean mChildAdded;
    private boolean mChildFocusable;
    private View mOwnerFocusBeforeChild;

    private View mTransientView;
    private WindowManager.LayoutParams mTransientParams;
    private boolean mTransientRequested;
    private boolean mTransientAdded;
    private long mTransientDurationMillis;
    private final Runnable mTransientTimeout = this::hideTransient;

    private DesktopDialogPresenter.Factory mDialogFactory;
    private AlertDialog mDialog;
    private View mTextInputView;
    private InputConnection mTextInputConnection;

    DesktopPanelWindowController(
            final Context context,
            final int displayId,
            final PanelVisibilityListener panelVisibilityListener) {
        mDisplayId = displayId;
        mPanelVisibilityListener = panelVisibilityListener;
        final DisplayManager displayManager = context.getSystemService(
                DisplayManager.class);
        final Display display = displayManager == null
                ? null : displayManager.getDisplay(displayId);
        if (display == null) {
            Log.w(TAG, "display not found: " + displayId);
            CompatibilityDiagnostics.record(
                    "PANEL-002", "Cannot create desktop panels",
                    "Display not found: " + displayId);
            mReleased = true;
            return;
        }
        final HostRegistration host;
        synchronized (REGISTRY_LOCK) {
            CONTROLLERS.put(Integer.valueOf(displayId), this);
            host = HOSTS.get(Integer.valueOf(displayId));
        }
        if (host != null) {
            attachHost(host.activity, host.windowManager, host.windowToken);
        }
    }

    static void registerActivity(
            final int displayId,
            final DesktopChromeActivity activity,
            final WindowManager windowManager,
            final IBinder windowToken) {
        if (displayId == Display.INVALID_DISPLAY || activity == null
                || windowManager == null || windowToken == null) {
            return;
        }
        final DesktopPanelWindowController controller;
        synchronized (REGISTRY_LOCK) {
            HOSTS.put(Integer.valueOf(displayId), new HostRegistration(
                    activity, windowManager, windowToken));
            controller = CONTROLLERS.get(Integer.valueOf(displayId));
        }
        if (controller != null) {
            controller.attachHost(activity, windowManager, windowToken);
        }
    }

    static void unregisterActivity(
            final int displayId,
            final DesktopChromeActivity activity) {
        final DesktopPanelWindowController controller;
        synchronized (REGISTRY_LOCK) {
            final HostRegistration host = HOSTS.get(Integer.valueOf(displayId));
            if (host != null && host.activity == activity) {
                HOSTS.remove(Integer.valueOf(displayId));
            }
            controller = CONTROLLERS.get(Integer.valueOf(displayId));
        }
        if (controller != null) {
            controller.detachHost(activity);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    boolean show(final View panel, final int left, final int top,
            final int width, final int height, final boolean focusable,
            final String title) {
        return show(panel, left, top, width, height, focusable, true, title);
    }

    // The listener observes ACTION_OUTSIDE only and does not implement clicks.
    @SuppressLint("ClickableViewAccessibility")
    boolean show(final View panel, final int left, final int top,
            final int width, final int height, final boolean focusable,
            final boolean inputMethodTarget, final String title) {
        if (mReleased || panel == null || width <= 0 || height <= 0) {
            return false;
        }
        final boolean replacingSamePanel = mVisibleRequested
                && mVisiblePanel == panel;
        hideAll(replacingSamePanel);
        dismissDialog();

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
        final WindowManager.LayoutParams params = createParams(
                width, height, flags, left, top, title);
        params.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;

        panel.setVisibility(View.VISIBLE);
        panel.setOnTouchListener((target, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                    && target == mVisiblePanel
                    && !mChildBounds.contains(
                            Math.round(event.getRawX()),
                            Math.round(event.getRawY()))
                    && !mInteractionOwnerBounds.contains(
                            Math.round(event.getRawX()),
                            Math.round(event.getRawY()))) {
                hideAll();
            }
            return false;
        });
        mVisiblePanel = panel;
        mVisibleTitle = safeTitle(title);
        mVisibleParams = params;
        mVisibleRequested = true;
        mVisibleFocusable = focusable;
        mBounds.set(left, top, left + width, top + height);
        if (!replacingSamePanel) {
            notifyPanelVisibilityChanged(panel, true);
        }
        if (!ensureHostAndAttach()) {
            clearVisibleRequest(true);
            return false;
        }
        return true;
    }

    /** Shows one modal child while retaining its owning desktop panel. */
    @SuppressLint("ClickableViewAccessibility")
    boolean showChild(final View panel, final int left, final int top,
            final int width, final int height, final String title,
            final ChildInputListener inputListener) {
        if (!mVisibleRequested || mVisiblePanel == null) {
            return show(panel, left, top, width, height, false, title);
        }
        if (mReleased || panel == null || width <= 0 || height <= 0) {
            return false;
        }
        final View ownerFocus = mChildRequested
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
        final WindowManager.LayoutParams params = createParams(
                hostBounds.width(), hostBounds.height(), flags,
                hostBounds.left, hostBounds.top, title);

        panel.setVisibility(View.VISIBLE);
        host.setOnTouchListener((target, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE
                    || target != mChildHost) {
                return false;
            }
            final int x = Math.round(event.getRawX());
            final int y = Math.round(event.getRawY());
            if (mInteractionOwnerBounds.contains(x, y)) {
                hideChild();
            } else {
                hideAll();
            }
            return false;
        });
        mChildPanel = panel;
        mChildHost = host;
        mChildTitle = safeTitle(title);
        mChildParams = params;
        mChildRequested = true;
        mChildFocusable = mVisibleFocusable;
        mChildBounds.set(left, top, left + width, top + height);
        if (!ensureHostAndAttach()) {
            clearChildRequest();
            restoreOwnerFocus(ownerFocus);
            return false;
        }
        return true;
    }

    boolean showTransient(final View view, final int left, final int top,
            final int width, final int height, final long durationMillis,
            final String title) {
        if (mReleased || view == null || width <= 0 || height <= 0) {
            return false;
        }
        hideTransient();
        final int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mTransientParams = createParams(
                width, height, flags, left, top, title);
        mTransientView = view;
        mTransientDurationMillis = durationMillis;
        mTransientRequested = true;
        view.setVisibility(View.VISIBLE);
        if (!ensureHostAndAttach()) {
            clearTransientRequest();
            return false;
        }
        return true;
    }

    boolean showDialog(final DesktopDialogPresenter.Factory factory) {
        if (mReleased || factory == null) {
            return false;
        }
        hideAll(false);
        dismissDialog();
        mDialogFactory = factory;
        if (!ensureHostAndAttach()) {
            mDialogFactory = null;
            return false;
        }
        return true;
    }

    void hideTransient() {
        mMainHandler.removeCallbacks(mTransientTimeout);
        final View view = mTransientView;
        if (mTransientAdded && view != null && mWindowManager != null) {
            removeView(view, "transient panel");
        }
        if (view != null) {
            view.setVisibility(View.GONE);
        }
        clearTransientRequest();
    }

    void hide(final View panel) {
        if (panel != null && panel == mChildPanel) {
            hideChild();
        } else if (panel != null && panel == mVisiblePanel) {
            hideAll();
        }
    }

    void hideTop() {
        if (mDialog != null) {
            mDialog.dismiss();
        } else if (mChildRequested && mChildPanel != null) {
            hideChild();
        } else {
            hideAll();
        }
    }

    void hideAll() {
        hideAll(false);
        dismissDialog();
    }

    private void hideAll(final boolean preservePanelVisibility) {
        hideChild(false);
        hideTransient();
        clearTextInputConnection();
        final View panel = mVisiblePanel;
        final boolean wasRequested = mVisibleRequested && panel != null;
        if (mVisibleAdded && panel != null && mWindowManager != null) {
            recordPanelState(false, mVisibleTitle, mBounds);
            removeView(panel, "panel");
        }
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        clearVisibleRequest(false);
        if (wasRequested && !preservePanelVisibility) {
            notifyPanelVisibilityChanged(panel, false);
        }
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
            removeView(host, "child panel");
        }
        if (host != null && panel != null && panel.getParent() == host) {
            host.removeView(panel);
        }
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        clearChildRequest();
        if (restoreOwnerFocus && wasFocusable) {
            restoreOwnerFocus(ownerFocus);
        }
    }

    void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        synchronized (REGISTRY_LOCK) {
            if (CONTROLLERS.get(Integer.valueOf(mDisplayId)) == this) {
                CONTROLLERS.remove(Integer.valueOf(mDisplayId));
            }
        }
        hideAll(false);
        dismissDialog();
        mInteractionOwnerBounds.setEmpty();
        clearHost();
    }

    void setInteractionOwnerBounds(final Rect bounds) {
        if (bounds == null) {
            mInteractionOwnerBounds.setEmpty();
        } else {
            mInteractionOwnerBounds.set(bounds);
        }
    }

    boolean hasVisiblePanel() {
        return (mChildAdded && mChildPanel != null)
                || (mVisibleAdded && mVisiblePanel != null);
    }

    Rect visibleBounds() {
        if (mChildAdded) {
            return new Rect(mChildBounds);
        }
        return mVisibleAdded ? new Rect(mBounds) : new Rect();
    }

    String visibleTitle() {
        if (mChildAdded) {
            return mChildTitle;
        }
        return mVisibleAdded ? mVisibleTitle : "";
    }

    boolean isVisible(final View panel) {
        return panel != null
                && ((panel == mChildPanel && mChildAdded)
                        || (panel == mVisiblePanel && mVisibleAdded));
    }

    boolean isRequested(final View panel) {
        return panel != null
                && ((panel == mChildPanel && mChildRequested)
                        || (panel == mVisiblePanel && mVisibleRequested));
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
                        eventTime, eventTime, arg1, arg2, 0, arg3));
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
                || (mVisibleAdded && mBounds.contains(roundedX, roundedY));
    }

    boolean containsVisiblePanelView(final View view) {
        return isDescendantOf(view, mChildAdded ? mChildPanel : null)
                || isDescendantOf(view,
                        mVisibleAdded ? mVisiblePanel : null);
    }

    boolean isTopPanelFocusable() {
        if (mChildAdded) {
            return mChildFocusable;
        }
        return mVisibleAdded && mVisibleFocusable;
    }

    private void attachHost(
            final DesktopChromeActivity activity,
            final WindowManager windowManager,
            final IBinder windowToken) {
        if (mReleased) {
            return;
        }
        final DesktopChromeActivity previous = mHostActivity;
        if (previous != null && previous != activity) {
            Log.w(TAG, "rejecting duplicate desktop chrome host display="
                    + mDisplayId);
            return;
        }
        mHostActivity = activity;
        mWindowManager = windowManager;
        mWindowToken = windowToken;
        mHostLaunchRequested = false;
        attachRequestedWindows();
    }

    private void detachHost(final DesktopChromeActivity activity) {
        if (mHostActivity != activity) {
            return;
        }
        final boolean visibleAdded = mVisibleAdded;
        final boolean childAdded = mChildAdded;
        final AlertDialog dialog = mDialog;
        mVisibleAdded = false;
        mChildAdded = false;
        mTransientAdded = false;
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            mDialog = null;
        }
        clearHost();
        if (childAdded) {
            recordPanelState(false, mChildTitle, mChildBounds);
        }
        if (visibleAdded) {
            recordPanelState(false, mVisibleTitle, mBounds);
        }
        hideAll(false);
        mDialogFactory = null;
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private void failHostLaunch(final String detail) {
        if (mReleased) {
            return;
        }
        mHostLaunchRequested = false;
        CompatibilityDiagnostics.record(
                "PANEL-004", "A desktop panel could not be shown", detail);
        hideAll(false);
        dismissDialog();
        clearHost();
    }

    private void clearHost() {
        mHostActivity = null;
        mWindowManager = null;
        mWindowToken = null;
        mHostLaunchRequested = false;
    }

    private boolean ensureHostAndAttach() {
        if (mReleased) {
            return false;
        }
        if (mHostActivity != null && mWindowManager != null
                && mWindowToken != null) {
            return attachRequestedWindows();
        }
        if (mHostLaunchRequested) {
            return true;
        }
        mHostLaunchRequested = true;
        MagicDeskRuntime.prepareDesktopChromeHost(
                mDisplayId,
                result -> {
                    if (mReleased) {
                        return;
                    }
                    mHostLaunchRequested = false;
                    if (result == null || !result.success) {
                        failHostLaunch(result == null
                                ? "desktop chrome host returned no result"
                                : result.message);
                        return;
                    }
                    if (mHostActivity != null
                            && mWindowManager != null
                            && mWindowToken != null) {
                        attachRequestedWindows();
                    }
                });
        return true;
    }

    private boolean attachRequestedWindows() {
        if (mWindowManager == null || mWindowToken == null) {
            return false;
        }
        boolean success = true;
        if (mVisibleRequested && !mVisibleAdded) {
            success &= addVisiblePanel();
        }
        if (mChildRequested && !mChildAdded) {
            success &= addChildPanel();
        }
        if (mTransientRequested && !mTransientAdded) {
            success &= addTransientPanel();
        }
        if (mDialogFactory != null && mDialog == null) {
            success &= createDialog();
        }
        return success;
    }

    private boolean addVisiblePanel() {
        final View panel = mVisiblePanel;
        if (!addView(panel, mVisibleParams, mVisibleTitle, "panel")) {
            clearVisibleRequest(true);
            return false;
        }
        mVisibleAdded = true;
        recordPanelState(true, mVisibleTitle, mBounds);
        requestFrame(panel, mVisibleParams, mVisibleTitle);
        return true;
    }

    private boolean addChildPanel() {
        final FrameLayout host = mChildHost;
        if (!addView(host, mChildParams, mChildTitle, "child panel")) {
            clearChildRequest();
            return false;
        }
        mChildAdded = true;
        recordPanelState(true, mChildTitle, mChildBounds);
        requestFrame(host, mChildParams, mChildTitle);
        return true;
    }

    private boolean addTransientPanel() {
        final View view = mTransientView;
        if (!addView(view, mTransientParams, "transient", "transient panel")) {
            clearTransientRequest();
            return false;
        }
        mTransientAdded = true;
        if (mTransientDurationMillis > 0) {
            mMainHandler.postDelayed(
                    mTransientTimeout, mTransientDurationMillis);
        }
        return true;
    }

    private boolean createDialog() {
        final DesktopDialogPresenter.Factory factory = mDialogFactory;
        final DesktopChromeActivity activity = mHostActivity;
        if (factory == null || activity == null) {
            return false;
        }
        try {
            final AlertDialog dialog = factory.create(activity);
            if (dialog == null) {
                mDialogFactory = null;
                return false;
            }
            mDialog = dialog;
            dialog.setOnDismissListener(ignored -> {
                if (mDialog == dialog) {
                    mDialog = null;
                    mDialogFactory = null;
                }
            });
            dialog.show();
            return true;
        } catch (RuntimeException error) {
            mDialog = null;
            mDialogFactory = null;
            Log.w(TAG, "failed to show desktop dialog", error);
            CompatibilityDiagnostics.record(
                    "PANEL-011", "A desktop dialog could not be shown",
                    "display=" + mDisplayId, error);
            return false;
        }
    }

    private boolean addView(
            final View view,
            final WindowManager.LayoutParams params,
            final String title,
            final String kind) {
        if (view == null || params == null || mWindowManager == null
                || mWindowToken == null) {
            return false;
        }
        params.token = mWindowToken;
        try {
            mWindowManager.addView(view, params);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "failed to show " + kind + " " + title, error);
            CompatibilityDiagnostics.record(
                    "PANEL-004", "A desktop panel could not be shown",
                    "panel=" + title, error);
            return false;
        }
    }

    private void requestFrame(
            final View view,
            final WindowManager.LayoutParams params,
            final String title) {
        view.postOnAnimation(() -> {
            if (!view.isAttachedToWindow() || mWindowManager == null) {
                return;
            }
            view.invalidate();
            try {
                mWindowManager.updateViewLayout(view, params);
            } catch (RuntimeException error) {
                Log.w(TAG, "failed to request panel frame " + title, error);
                CompatibilityDiagnostics.record(
                        "PANEL-003", "A desktop panel could not be redrawn",
                        "panel=" + title, error);
            }
        });
    }

    private WindowManager.LayoutParams createParams(
            final int width,
            final int height,
            final int flags,
            final int left,
            final int top,
            final String title) {
        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = left;
        params.y = top;
        params.setFitInsetsTypes(0);
        params.setTitle(title);
        return params;
    }

    private void removeView(final View view, final String kind) {
        try {
            mWindowManager.removeViewImmediate(view);
        } catch (RuntimeException error) {
            Log.w(TAG, "failed to remove " + kind, error);
        }
    }

    private void dismissDialog() {
        final AlertDialog dialog = mDialog;
        mDialog = null;
        mDialogFactory = null;
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            dialog.dismiss();
        }
    }

    private void clearVisibleRequest(final boolean notifyHidden) {
        final View panel = mVisiblePanel;
        final boolean wasRequested = mVisibleRequested;
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        mVisiblePanel = null;
        mVisibleTitle = "";
        mVisibleParams = null;
        mVisibleRequested = false;
        mVisibleAdded = false;
        mVisibleFocusable = false;
        mBounds.setEmpty();
        if (notifyHidden && wasRequested) {
            notifyPanelVisibilityChanged(panel, false);
        }
    }

    private void clearChildRequest() {
        final View panel = mChildPanel;
        final FrameLayout host = mChildHost;
        if (host != null && panel != null && panel.getParent() == host) {
            host.removeView(panel);
        }
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        mChildPanel = null;
        mChildHost = null;
        mChildTitle = "";
        mChildParams = null;
        mChildRequested = false;
        mChildAdded = false;
        mChildFocusable = false;
        mOwnerFocusBeforeChild = null;
        mChildBounds.setEmpty();
    }

    private void clearTransientRequest() {
        if (mTransientView != null) {
            mTransientView.setVisibility(View.GONE);
        }
        mTransientView = null;
        mTransientParams = null;
        mTransientRequested = false;
        mTransientAdded = false;
        mTransientDurationMillis = 0;
    }

    private void restoreOwnerFocus(final View ownerFocus) {
        if (!mVisibleAdded || !mVisibleFocusable || mVisiblePanel == null) {
            return;
        }
        final View target = ownerFocus == null ? mVisiblePanel : ownerFocus;
        target.post(() -> {
            if (mVisibleAdded && !mChildAdded
                    && target.isAttachedToWindow()) {
                target.requestFocusFromTouch();
            }
        });
    }

    private View topInputPanel() {
        if (mChildAdded && mChildPanel != null) {
            return mChildPanel;
        }
        return mVisibleAdded ? mVisiblePanel : null;
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
            return handleOutsideEvent(event) || super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(final MotionEvent event) {
            return handleOutsideEvent(event)
                    || super.dispatchGenericMotionEvent(event);
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
                post(DesktopPanelWindowController.this::hideChild);
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

    private void notifyPanelVisibilityChanged(
            final View panel,
            final boolean visible) {
        if (mPanelVisibilityListener != null && panel != null) {
            mPanelVisibilityListener.onPanelVisibilityChanged(panel, visible);
        }
    }

    private static String safeText(final String text) {
        return text == null ? "" : text;
    }

    private static String safeTitle(final String title) {
        return title == null ? "" : title;
    }

    private static final class HostRegistration {
        final DesktopChromeActivity activity;
        final WindowManager windowManager;
        final IBinder windowToken;

        HostRegistration(
                final DesktopChromeActivity activity,
                final WindowManager windowManager,
                final IBinder windowToken) {
            this.activity = activity;
            this.windowManager = windowManager;
            this.windowToken = windowToken;
        }
    }

    private void recordPanelState(
            final boolean visible,
            final String title,
            final Rect bounds) {
        try {
            DesktopAutomationEventJournal.record(
                    "ui", visible ? "popup_shown" : "popup_hidden",
                    true, title,
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
