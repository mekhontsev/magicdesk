package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

/** Keeps a hidden taskbar reachable from a passive strip at the screen edge. */
final class DesktopTaskbarRevealController {
    private static final int EDGE_STRIP_HEIGHT_PX = 1;
    private static final long REVEAL_DWELL_MILLIS = 450L;
    private static final long HIDE_DELAY_MILLIS = 300L;

    private final DesktopShellActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final PointerEdgeRevealState mState =
            new PointerEdgeRevealState();

    private boolean mPolicyVisible = true;
    private boolean mForcedVisible;
    private boolean mStarted;
    private boolean mReleased;

    private final Runnable mRevealTimeout = () -> {
        if (!mReleased && mState.onRevealTimeout()) {
            applyPresentation();
        }
    };

    private final Runnable mHideTimeout = () -> {
        if (!mReleased && mState.onHideTimeout()) {
            applyPresentation();
        }
    };

    DesktopTaskbarRevealController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void start() {
        if (mStarted || mReleased) {
            return;
        }
        mStarted = true;
        mActivity.taskbar().setEdgeHoverListener(this::onHoverEvent);
        mState.setArmed(!mPolicyVisible && !mForcedVisible);
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
        mState.setArmed(!visible && !mForcedVisible);
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
        mState.setArmed(!mPolicyVisible && !visible);
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
        mActivity.taskbar().setEdgeHoverListener(null);
    }

    private void onHoverEvent(final MotionEvent event) {
        if (mReleased || mPolicyVisible || mForcedVisible
                || event == null) {
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                applyTimerAction(mState.onPointerEntered());
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (isRelayoutExit(event)) {
                    // Nubia's touch panel ends the old hover stream when the
                    // taskbar window moves up, although the pointer remains
                    // inside the newly exposed taskbar.
                    applyTimerAction(mState.onPointerEntered());
                    break;
                }
                applyTimerAction(mState.onPointerExited());
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
                applyTimerAction(mState.onPointerExited());
                break;
            default:
                break;
        }
    }

    private boolean isRelayoutExit(final MotionEvent event) {
        if (!mState.isRevealed()) {
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
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (taskbar == null || overlays == null) {
            return;
        }
        final boolean visible = mForcedVisible
                || mPolicyVisible
                || mState.isRevealed();
        final Rect normalBounds = mActivity.getTaskbarBounds();
        if (visible) {
            overlays.updatePersistentBounds(
                    normalBounds.left,
                    normalBounds.top,
                    normalBounds.width(),
                    normalBounds.height());
            taskbar.setEdgeHidden(false);
        } else {
            taskbar.setEdgeHidden(true);
            overlays.updatePersistentBounds(
                    normalBounds.left,
                    normalBounds.bottom - EDGE_STRIP_HEIGHT_PX,
                    normalBounds.width(),
                    normalBounds.height());
        }
        overlays.setPersistentVisible(true);
    }

    private void cancelTimers() {
        mHandler.removeCallbacks(mRevealTimeout);
        mHandler.removeCallbacks(mHideTimeout);
    }
}
