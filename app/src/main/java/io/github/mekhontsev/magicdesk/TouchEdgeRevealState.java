package io.github.mekhontsev.magicdesk;

/** Resolves a bottom-edge touch sequence into explicit reveal state. */
final class TouchEdgeRevealState {
    enum Action {
        NONE,
        REVEAL,
        DISMISS
    }

    private boolean mArmed;
    private boolean mTracking;
    private boolean mRevealed;
    private boolean mDismissOnUp;
    private float mDownX;
    private float mDownY;

    void setArmed(final boolean armed) {
        mArmed = armed;
        mTracking = false;
        mRevealed = false;
        mDismissOnUp = false;
    }

    Action onDown(final float x, final float y) {
        if (!mArmed) {
            return Action.NONE;
        }
        mDismissOnUp = mRevealed;
        mTracking = !mRevealed;
        mDownX = x;
        mDownY = y;
        return Action.NONE;
    }

    Action onMove(
            final float x,
            final float y,
            final int touchSlop) {
        if (!mArmed || !mTracking || mRevealed) {
            return Action.NONE;
        }
        final float upwardDistance = mDownY - y;
        final float horizontalDistance = Math.abs(x - mDownX);
        if (upwardDistance < Math.max(1, touchSlop)
                || upwardDistance <= horizontalDistance) {
            return Action.NONE;
        }
        mTracking = false;
        mRevealed = true;
        return Action.REVEAL;
    }

    Action onUp() {
        mTracking = false;
        if (!mArmed || !mDismissOnUp || !mRevealed) {
            mDismissOnUp = false;
            return Action.NONE;
        }
        mDismissOnUp = false;
        mRevealed = false;
        return Action.DISMISS;
    }

    Action onCancel() {
        mTracking = false;
        mDismissOnUp = false;
        return Action.NONE;
    }

    Action onOutside() {
        mTracking = false;
        mDismissOnUp = false;
        if (!mArmed || !mRevealed) {
            return Action.NONE;
        }
        mRevealed = false;
        return Action.DISMISS;
    }

    boolean isRevealed() {
        return mRevealed;
    }
}
