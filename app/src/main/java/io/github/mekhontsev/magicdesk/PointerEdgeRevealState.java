package io.github.mekhontsev.magicdesk;

/** State machine for revealing a hidden taskbar from its screen-edge strip. */
final class PointerEdgeRevealState {
    enum TimerAction {
        NONE,
        START_REVEAL,
        CANCEL_REVEAL,
        START_HIDE,
        CANCEL_HIDE
    }

    private boolean mArmed;
    private boolean mPointerInside;
    private boolean mRevealed;
    private boolean mRevealPending;
    private boolean mHidePending;

    void setArmed(final boolean armed) {
        if (mArmed == armed) {
            return;
        }
        mArmed = armed;
        // Pointer presence is independent of the current visibility policy.
        // If policy changes while the pointer is already over the taskbar,
        // keep it exposed until a real exit instead of hiding under it.
        mRevealed = armed && mPointerInside;
        mRevealPending = false;
        mHidePending = false;
    }

    TimerAction onPointerEntered() {
        mPointerInside = true;
        if (!mArmed) {
            return TimerAction.NONE;
        }
        if (mRevealed) {
            if (mHidePending) {
                mHidePending = false;
                return TimerAction.CANCEL_HIDE;
            }
            return TimerAction.NONE;
        }
        if (!mRevealPending) {
            mRevealPending = true;
            return TimerAction.START_REVEAL;
        }
        return TimerAction.NONE;
    }

    TimerAction onPointerExited() {
        mPointerInside = false;
        if (!mArmed) {
            return TimerAction.NONE;
        }
        if (mRevealPending) {
            mRevealPending = false;
            return TimerAction.CANCEL_REVEAL;
        }
        if (mRevealed && !mHidePending) {
            mHidePending = true;
            return TimerAction.START_HIDE;
        }
        return TimerAction.NONE;
    }

    boolean onRevealTimeout() {
        if (!mArmed || !mPointerInside || !mRevealPending || mRevealed) {
            return false;
        }
        mRevealPending = false;
        mRevealed = true;
        return true;
    }

    boolean onHideTimeout() {
        if (!mArmed || mPointerInside || !mHidePending || !mRevealed) {
            return false;
        }
        mHidePending = false;
        mRevealed = false;
        return true;
    }

    boolean isRevealed() {
        return mRevealed;
    }
}
