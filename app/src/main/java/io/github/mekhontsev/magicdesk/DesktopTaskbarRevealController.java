package io.github.mekhontsev.magicdesk;

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
    private boolean mAutoHide;
    private boolean mClickReveal;
    private boolean mAutomaticHold;
    private boolean mInteractionHold;
    private boolean mStarted;
    private boolean mReleased;
    private ShellPanel.Edge mTouchEdge;

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
        updateArmedState();
        if (mStarted) {
            applyPresentation();
        }
    }

    void setClickReveal(final boolean enabled) {
        mClickReveal = enabled;
    }

    void setAutoHide(final boolean enabled) {
        if (mReleased || mAutoHide == enabled) {
            return;
        }
        mAutoHide = enabled;
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
            case MotionEvent.ACTION_OUTSIDE:
                applyTimerAction(contains(event.getRawX(), event.getRawY())
                        ? mPointerState.onPointerEntered() : mPointerState.onPointerExited());
                break;
            case MotionEvent.ACTION_DOWN:
                if (mClickReveal && event.isFromSource(InputDevice.SOURCE_MOUSE)
                        && (event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0
                        && contains(event.getRawX(), event.getRawY())
                        && mPointerState.onPointerClick()) {
                    cancelTimers();
                    applyPresentation();
                    break;
                }
                // Clicking is an optional immediate reveal. Only hover starts
                // dwell; ordinary clicks must not accidentally enable it.
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (currentPresentation() == Presentation.VISIBLE) {
                    applyTimerAction(contains(event.getRawX(), event.getRawY())
                            ? mPointerState.onPointerEntered() : mPointerState.onPointerExited());
                }
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
            case MotionEvent.ACTION_CANCEL:
                // Exit may carry the last interior position, not a new hit-test location.
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
                mPolicyVisible, mAutomaticHold,
                mPointerState.isRevealed(), isExplicitlyRevealed()));
        final TaskbarController taskbar = mActivity.taskbar();
        final DesktopTaskbarHost taskbarHost = mActivity.taskbarHost();
        if (taskbar == null || taskbarHost == null) {
            return;
        }
        final Presentation presentation = currentPresentation();
        taskbarHost.setPresented(true);
        if (presentation == Presentation.VISIBLE) {
            taskbarHost.setEdgeHidden(false, 1);
        } else {
            taskbarHost.setEdgeHidden(true, edgeThickness());
        }
    }

    private int edgeThickness() {
        return mTouchEdgeEnabled ? Math.max(1, mTouchEdgeHeight) : EDGE_STRIP_HEIGHT_PX;
    }

    private boolean contains(final float x, final float y) {
        final DesktopTaskbarHost host = mActivity.taskbarHost();
        final Presentation presentation = currentPresentation();
        return host != null
                && host.contains(x, y, presentation == Presentation.EDGE, edgeThickness());
    }

    private ShellPanel.Edge edgeAt(final float x, final float y) {
        final DesktopTaskbarHost host = mActivity.taskbarHost();
        if (host == null) return null;
        for (DesktopTaskbarHost.Panel panel : host.panels()) {
            final var output = panel.output();
            final var paint = panel.paint();
            final var bounds = PanelGeometry.presented(
                    new ShellBounds(output.left, output.top, output.right, output.bottom),
                    new ShellBounds(paint.left, paint.top, paint.right, paint.bottom), panel.edge(),
                    true, currentPresentation() == Presentation.EDGE, edgeThickness());
            if (x >= bounds.left() && x < bounds.right() && y >= bounds.top() && y < bounds.bottom()) {
                return panel.edge();
            }
        }
        return null;
    }

    static float gestureX(final ShellPanel.Edge edge, final float x, final float y) {
        return edge.vertical() ? y : x;
    }

    static float gestureY(final ShellPanel.Edge edge, final float x, final float y) {
        return switch (edge) {
            case TOP -> -y;
            case BOTTOM -> y;
            case LEFT -> -x;
            case RIGHT -> x;
        };
    }

    private void cancelTimers() {
        mHandler.removeCallbacks(mRevealTimeout);
        mHandler.removeCallbacks(mHideTimeout);
    }

    private boolean isExplicitlyRevealed() {
        return mInteractionHold || mTouchState.isRevealed();
    }

    private Presentation currentPresentation() {
        return resolvePresentation(mPolicyVisible, mAutoHide, mAutomaticHold,
                mPointerState.isRevealed(), isExplicitlyRevealed());
    }

    static Set<ShellSurface.Layer> resolveShellLayers(boolean policyVisible,
            boolean automaticHold, boolean pointerRevealed, boolean explicitlyRevealed) {
        // HOME layers remain naturally occluded by Android tasks. Taskbar auto-hide is a
        // preference for the native taskbar, not a request to hide every external panel.
        if (policyVisible || automaticHold || pointerRevealed || explicitlyRevealed) {
            return Set.of(ShellSurface.Layer.values());
        }
        return Set.of(ShellSurface.Layer.BACKGROUND, ShellSurface.Layer.BOTTOM, ShellSurface.Layer.OVERLAY);
    }

    static Presentation resolvePresentation(
            final boolean policyVisible,
            final boolean autoHide,
            final boolean automaticHold,
            final boolean pointerRevealed,
            final boolean explicitlyRevealed) {
        return automaticHold || (policyVisible && !autoHide) || pointerRevealed || explicitlyRevealed
                ? Presentation.VISIBLE : Presentation.EDGE;
    }

    private void updateArmedState() {
        final boolean armed = resolvePresentation(
                mPolicyVisible, mAutoHide, mAutomaticHold,
                false, mInteractionHold) == Presentation.EDGE;
        if (mPointerState.setArmed(armed)) {
            cancelTimers();
        }
        // A navigation reveal lasts until user input, across HOME visibility changes.
        mTouchState.setArmed(mTouchEdgeEnabled && armed);
    }

    private boolean handleTouchEdgeInput(final MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_OUTSIDE) {
            // Sibling native windows receive outside notifications for the same touch.
            // A touch inside another panel is neither a dismissal nor pointer dwell.
            if (contains(event.getRawX(), event.getRawY())) {
                return mTouchEdgeEnabled && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
            }
            mTouchEdge = null;
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
                mTouchEdge = edgeAt(event.getRawX(), event.getRawY());
                if (mTouchEdge == null) return false;
                applyTouchAction(mTouchState.onDown(
                        gestureX(mTouchEdge, event.getRawX(), event.getRawY()),
                        gestureY(mTouchEdge, event.getRawX(), event.getRawY())), false);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mTouchEdge == null) return false;
                applyTouchAction(mTouchState.onMove(
                        gestureX(mTouchEdge, event.getRawX(), event.getRawY()),
                        gestureY(mTouchEdge, event.getRawX(), event.getRawY()), mTouchSlop), false);
                break;
            case MotionEvent.ACTION_UP:
                mTouchEdge = null;
                applyTouchAction(mTouchState.onUp(), true);
                break;
            case MotionEvent.ACTION_CANCEL:
                mTouchEdge = null;
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
