package io.github.mekhontsev.magicdesk;

/** Preserves taskbar policy while a transient system layer has focus. */
final class DesktopTaskbarDialogHold {
    private static final int STATE_INACTIVE = 0;
    private static final int STATE_VISIBLE = 1;
    private static final int STATE_AWAITING_SNAPSHOT = 2;

    private int mState;
    private boolean mHeldVisibility;

    boolean setDialogVisible(
            final boolean visible,
            final boolean currentVisibility) {
        if (visible) {
            if (mState == STATE_VISIBLE) {
                return false;
            }
            if (mState == STATE_INACTIVE) {
                mHeldVisibility = currentVisibility;
            }
            mState = STATE_VISIBLE;
            return true;
        }
        if (mState != STATE_VISIBLE) {
            return false;
        }
        mState = STATE_AWAITING_SNAPSHOT;
        return true;
    }

    boolean currentVisibility(final boolean candidate) {
        return mState == STATE_INACTIVE ? candidate : mHeldVisibility;
    }

    boolean applySnapshot(final boolean candidate) {
        if (mState == STATE_VISIBLE) {
            return mHeldVisibility;
        }
        if (mState == STATE_AWAITING_SNAPSHOT) {
            mState = STATE_INACTIVE;
        }
        return candidate;
    }
}
