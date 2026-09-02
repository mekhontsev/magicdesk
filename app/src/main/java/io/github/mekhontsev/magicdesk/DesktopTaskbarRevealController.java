package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/** Keeps a hidden taskbar reachable from a passive strip at the screen edge. */
final class DesktopTaskbarRevealController {
    private static final int EDGE_STRIP_HEIGHT_PX = 1;
    private static final long REVEAL_DWELL_MILLIS = 450L;
    private static final long HIDE_DELAY_MILLIS = 300L;

    private final DesktopShellActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PointerEdgeRevealState mPointerState =
            new PointerEdgeRevealState();
    private final TouchEdgeRevealState mTouchState =
            new TouchEdgeRevealState();
    private final boolean mTouchEdgeEnabled;
    private final int mTouchSlop;
    private final int mTouchEdgeHeight;

    private boolean mPolicyVisible = true;
    private boolean mDesktopPlaneForeground = true;
    private boolean mAutoHide;
    private boolean mForcedVisible;
    private boolean mStarted;
    private boolean mReleased;

    private final Runnable mRevealTimeout = () -> {
        if (!mReleased && mPointerState.onRevealTimeout()) {
            applyPresentation();
        }
    };

    private final Runnable mHideTimeout = () -> {
        if (!mReleased && mPointerState.onHideTimeout()) {
            applyPresentation();
        }
    };

    DesktopTaskbarRevealController(final DesktopShellActivity activity) {
        mActivity = activity;
        mTouchEdgeEnabled = activity.getCurrentDisplayId()
                == Display.DEFAULT_DISPLAY;
        final ViewConfiguration configuration = ViewConfiguration.get(activity);
        mTouchSlop = configuration.getScaledTouchSlop();
        mTouchEdgeHeight = configuration.getScaledEdgeSlop();
    }

    void start() {
        if (mStarted || mReleased) {
            return;
        }
        mStarted = true;
        final DesktopTaskbarHost taskbarHost = mActivity.taskbarHost();
        if (taskbarHost == null) {
            throw new IllegalStateException("desktop taskbar host is missing");
        }
        taskbarHost.setEdgeInputListener(this::onEdgeInput);
        updateArmedState();
        applyPresentation();
    }

    void setPolicyVisible(final boolean visible) {
        if (mReleased) {
            return;
        }
        if (mPolicyVisible == visible) {
            return;
        }
        mPolicyVisible = visible;
        cancelTimers();
        updateArmedState();
        if (mStarted) {
            applyPresentation();
        }
    }

    void setDesktopPlaneForeground(final boolean foreground) {
        if (mReleased || mDesktopPlaneForeground == foreground) {
            return;
        }
        mDesktopPlaneForeground = foreground;
        cancelTimers();
        updateArmedState();
        if (mStarted) {
            applyPresentation();
        }
    }

    void setAutoHide(final boolean enabled) {
        if (mReleased || mAutoHide == enabled) {
            return;
        }
        mAutoHide = enabled;
        cancelTimers();
        updateArmedState();
        if (mStarted) {
            applyPresentation();
        }
    }

    void setForcedVisible(final boolean visible) {
        if (mReleased || mForcedVisible == visible) {
            return;
        }
        mForcedVisible = visible;
        cancelTimers();
        updateArmedState();
        if (mStarted) {
            applyPresentation();
        }
    }

    void updateViewport() {
        if (mStarted && !mReleased) {
            applyPresentation();
        }
    }

    void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        cancelTimers();
        final DesktopTaskbarHost taskbarHost = mActivity.taskbarHost();
        if (taskbarHost != null) {
            taskbarHost.setEdgeInputListener(null);
        }
    }

    private void onEdgeInput(final MotionEvent event) {
        if (mReleased || event == null) {
            return;
        }
        if (handleTouchEdgeInput(event)) {
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                applyTimerAction(mPointerState.onPointerEntered());
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (isRelayoutExit(event)) {
                    // Nubia's touch panel ends the old hover stream when the
                    // taskbar window moves up, although the pointer remains
                    // inside the newly exposed taskbar.
                    applyTimerAction(mPointerState.onPointerEntered());
                    break;
                }
                applyTimerAction(mPointerState.onPointerExited());
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
                applyTimerAction(mPointerState.onPointerExited());
                break;
            default:
                break;
        }
    }

    private boolean isRelayoutExit(final MotionEvent event) {
        if (!mPointerState.isRevealed()) {
            return false;
        }
        final Rect bounds = mActivity.getTaskbarBounds();
        return isBottomEdgeExit(
                bounds.left,
                bounds.right,
                bounds.bottom,
                event.getRawX(),
                event.getRawY());
    }

    static boolean isBottomEdgeExit(
            final int left,
            final int right,
            final int bottom,
            final float x,
            final float y) {
        return x >= left && x < right && y >= bottom;
    }

    private void applyTimerAction(
            final PointerEdgeRevealState.TimerAction action) {
        switch (action) {
            case START_REVEAL:
                mHandler.postDelayed(mRevealTimeout, REVEAL_DWELL_MILLIS);
                break;
            case CANCEL_REVEAL:
                mHandler.removeCallbacks(mRevealTimeout);
                break;
            case START_HIDE:
                mHandler.postDelayed(mHideTimeout, HIDE_DELAY_MILLIS);
                break;
            case CANCEL_HIDE:
                mHandler.removeCallbacks(mHideTimeout);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void applyPresentation() {
        final TaskbarController taskbar = mActivity.taskbar();
        final DesktopTaskbarHost taskbarHost = mActivity.taskbarHost();
        if (taskbar == null || taskbarHost == null) {
            return;
        }
        if (!mDesktopPlaneForeground) {
            taskbarHost.setPresented(false);
            return;
        }
        final boolean visible = mForcedVisible
                || isPinnedVisible()
                || mPointerState.isRevealed()
                || mTouchState.isRevealed();
        taskbarHost.setPresented(true);
        if (visible) {
            taskbar.setEdgeHidden(false);
            taskbarHost.setEdgeHidden(false, 1);
        } else {
            taskbar.setEdgeHidden(true);
            final Rect normalBounds = mActivity.getTaskbarBounds();
            final int hiddenEdgeHeight = mTouchEdgeEnabled
                    ? Math.max(1, Math.min(
                            normalBounds.height(), mTouchEdgeHeight))
                    : EDGE_STRIP_HEIGHT_PX;
            taskbarHost.setEdgeHidden(true, hiddenEdgeHeight);
        }
    }

    private void cancelTimers() {
        mHandler.removeCallbacks(mRevealTimeout);
        mHandler.removeCallbacks(mHideTimeout);
    }

    private boolean isPinnedVisible() {
        return mPolicyVisible && !mAutoHide;
    }

    private boolean shouldArm() {
        return mDesktopPlaneForeground
                && !mForcedVisible
                && !isPinnedVisible();
    }

    private void updateArmedState() {
        final boolean armed = shouldArm();
        mPointerState.setArmed(armed);
        mTouchState.setArmed(mTouchEdgeEnabled && armed);
    }

    private boolean handleTouchEdgeInput(final MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_OUTSIDE) {
            final TouchEdgeRevealState.Action result =
                    mTouchState.onOutside();
            applyTouchAction(result, false);
            // Let the pointer state observe the same outside event so a
            // preceding mouse reveal cannot keep the taskbar open.
            return false;
        }
        if (!mTouchEdgeEnabled
                || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            return false;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                applyTouchAction(mTouchState.onDown(
                        event.getRawX(), event.getRawY()), false);
                break;
            case MotionEvent.ACTION_MOVE:
                applyTouchAction(mTouchState.onMove(
                        event.getRawX(), event.getRawY(), mTouchSlop), false);
                break;
            case MotionEvent.ACTION_UP:
                applyTouchAction(mTouchState.onUp(), true);
                break;
            case MotionEvent.ACTION_CANCEL:
                applyTouchAction(mTouchState.onCancel(), false);
                break;
            default:
                break;
        }
        return true;
    }

    private void applyTouchAction(
            final TouchEdgeRevealState.Action action,
            final boolean afterDispatch) {
        if (action == TouchEdgeRevealState.Action.NONE) {
            return;
        }
        if (afterDispatch) {
            mHandler.post(() -> {
                if (!mReleased) {
                    applyPresentation();
                }
            });
        } else {
            applyPresentation();
        }
    }
}
