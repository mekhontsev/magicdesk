package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import java.util.Set;

/** Resolves automatic chrome visibility and explicit edge/navigation reveals. */
final class DesktopTaskbarRevealController {
    enum Presentation {
        UNAVAILABLE,
        EDGE,
        VISIBLE
    }

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
    private boolean mAvailable = true;
    private boolean mAutoHide;
    private boolean mAutomaticHold;
    private boolean mInteractionHold;
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

    void setAvailable(final boolean available) {
        if (mReleased || mAvailable == available) {
            return;
        }
        mAvailable = available;
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

    void setVisibilityHolds(final boolean automatic, final boolean interaction) {
        if (mReleased || (mAutomaticHold == automatic && mInteractionHold == interaction)) {
            return;
        }
        mAutomaticHold = automatic;
        mInteractionHold = interaction;
        // The open panel owns visibility until it closes, regardless of input source.
        if (interaction) {
            mTouchState.dismiss();
        }
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

    void reveal() {
        if (!mStarted || mReleased || !mTouchEdgeEnabled
                || currentPresentation() == Presentation.VISIBLE) {
            return;
        }
        applyTouchAction(mTouchState.reveal(), false);
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
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_OUTSIDE:
                applyTimerAction(mPointerState.onPointerExited());
                break;
            default:
                break;
        }
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
        mActivity.shellPresentation().update(resolveShellLayers(
                mAvailable, mPolicyVisible, mAutomaticHold,
                mPointerState.isRevealed(), isExplicitlyRevealed()));
        final TaskbarController taskbar = mActivity.taskbar();
        final DesktopTaskbarHost taskbarHost = mActivity.taskbarHost();
        if (taskbar == null || taskbarHost == null) {
            return;
        }
        final Presentation presentation = currentPresentation();
        if (presentation == Presentation.UNAVAILABLE) {
            taskbarHost.setPresented(false);
            taskbar.setEdgeHidden(false);
            taskbarHost.setEdgeHidden(false, 1);
            return;
        }
        taskbarHost.setPresented(true);
        if (presentation == Presentation.VISIBLE) {
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

    private boolean isExplicitlyRevealed() {
        return mInteractionHold || mTouchState.isRevealed();
    }

    private Presentation currentPresentation() {
        return resolvePresentation(mAvailable, mPolicyVisible, mAutoHide, mAutomaticHold,
                mPointerState.isRevealed(), isExplicitlyRevealed());
    }

    static Set<ShellSurface.Layer> resolveShellLayers(boolean available, boolean policyVisible,
            boolean automaticHold, boolean pointerRevealed, boolean explicitlyRevealed) {
        // HOME layers remain naturally occluded by Android tasks. Taskbar auto-hide is a
        // preference for the native taskbar, not a request to hide every external panel.
        if (explicitlyRevealed) return Set.of(ShellSurface.Layer.values());
        if (!available) return Set.of(ShellSurface.Layer.BACKGROUND, ShellSurface.Layer.BOTTOM);
        if (policyVisible || automaticHold || pointerRevealed) return Set.of(ShellSurface.Layer.values());
        return Set.of(ShellSurface.Layer.BACKGROUND, ShellSurface.Layer.BOTTOM, ShellSurface.Layer.OVERLAY);
    }

    static Presentation resolvePresentation(
            final boolean available,
            final boolean policyVisible,
            final boolean autoHide,
            final boolean automaticHold,
            final boolean pointerRevealed,
            final boolean explicitlyRevealed) {
        // Foreground task ownership suppresses automatic chrome, not a user
        // request to reveal it. The non-focusable host leaves that task alone.
        if (explicitlyRevealed) {
            return Presentation.VISIBLE;
        }
        if (!available) {
            return Presentation.UNAVAILABLE;
        }
        return automaticHold || (policyVisible && !autoHide) || pointerRevealed
                ? Presentation.VISIBLE : Presentation.EDGE;
    }

    private void updateArmedState() {
        final boolean armed = resolvePresentation(
                mAvailable, mPolicyVisible, mAutoHide, mAutomaticHold,
                false, mInteractionHold) == Presentation.EDGE;
        mPointerState.setArmed(armed);
        // A navigation reveal lasts until user input, across HOME visibility changes.
        mTouchState.setArmed(mTouchEdgeEnabled && armed);
    }

    private boolean handleTouchEdgeInput(final MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_OUTSIDE) {
            final TouchEdgeRevealState.Action result =
                    mTouchState.dismiss();
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
