package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Transient window-management state owned by one Android task id. */
final class DesktopTaskRuntimeState {
    private enum FullscreenTransition {
        NONE,
        ENTERING,
        RESTORING
    }

    static final class BoundsTransition {
        private final Rect mTargetBounds;
        final boolean clearsMaximizeState;

        BoundsTransition(
                final Rect targetBounds,
                final boolean clearsMaximizeState) {
            mTargetBounds = copy(targetBounds);
            this.clearsMaximizeState = clearsMaximizeState;
        }

        Rect targetBounds() {
            return copy(mTargetBounds);
        }
    }

    private final int mTaskId;

    private Rect mLastWindowBounds;
    private Rect mMaximizeRestoreBounds;
    private BoundsTransition mBoundsTransition;
    private Rect mWindowRestoreBounds;
    private Rect mFullscreenRestoreBounds;
    private Boolean mImmersiveRequested;
    private boolean mAppRequestedFullscreen;
    private FullscreenTransition mFullscreenTransition =
            FullscreenTransition.NONE;
    private boolean mManualImmersiveOverride;
    private boolean mStartupWindowed;

    DesktopTaskRuntimeState(final int taskId) {
        mTaskId = taskId;
    }

    int taskId() {
        return mTaskId;
    }

    synchronized Rect lastWindowBounds() {
        return copy(mLastWindowBounds);
    }

    synchronized void setLastWindowBounds(final Rect bounds) {
        mLastWindowBounds = copy(bounds);
    }

    synchronized Rect maximizeRestoreBounds() {
        return copy(mMaximizeRestoreBounds);
    }

    synchronized void setMaximizeRestoreBounds(final Rect bounds) {
        mMaximizeRestoreBounds = copy(bounds);
    }

    synchronized void clearMaximizeRestoreBounds() {
        mMaximizeRestoreBounds = null;
    }

    synchronized BoundsTransition beginBoundsTransition(
            final Rect targetBounds,
            final boolean clearsMaximizeState) {
        mBoundsTransition = new BoundsTransition(
                targetBounds, clearsMaximizeState);
        return mBoundsTransition;
    }

    synchronized BoundsTransition boundsTransition() {
        return mBoundsTransition;
    }

    synchronized boolean isBoundsTransition(
            final BoundsTransition transition) {
        return mBoundsTransition == transition;
    }

    synchronized void clearBoundsTransition(
            final BoundsTransition transition) {
        if (mBoundsTransition == transition) {
            mBoundsTransition = null;
        }
    }

    synchronized void clearNativeBoundsState() {
        mLastWindowBounds = null;
        mMaximizeRestoreBounds = null;
        mBoundsTransition = null;
    }

    synchronized Rect windowRestoreBounds() {
        return copy(mWindowRestoreBounds);
    }

    synchronized void setWindowRestoreBounds(final Rect bounds) {
        mWindowRestoreBounds = copy(bounds);
    }

    synchronized void clearWindowRestoreBounds() {
        mWindowRestoreBounds = null;
    }

    synchronized Rect fullscreenRestoreBounds() {
        return copy(mFullscreenRestoreBounds);
    }

    synchronized void setFullscreenRestoreBounds(final Rect bounds) {
        mFullscreenRestoreBounds = copy(bounds);
    }

    synchronized void clearFullscreenRestoreBounds() {
        mFullscreenRestoreBounds = null;
    }

    synchronized Boolean updateImmersiveRequested(
            final boolean requesting) {
        final Boolean previous = mImmersiveRequested;
        mImmersiveRequested = Boolean.valueOf(requesting);
        return previous;
    }

    synchronized boolean isImmersiveRequested() {
        return Boolean.TRUE.equals(mImmersiveRequested);
    }

    synchronized boolean isAppRequestedFullscreen() {
        return mAppRequestedFullscreen;
    }

    synchronized void setAppRequestedFullscreen(final boolean requested) {
        mAppRequestedFullscreen = requested;
    }

    synchronized boolean beginFullscreenTransition() {
        return beginFullscreenTransition(FullscreenTransition.ENTERING);
    }

    synchronized boolean beginFullscreenRestoreTransition() {
        return beginFullscreenTransition(FullscreenTransition.RESTORING);
    }

    private boolean beginFullscreenTransition(
            final FullscreenTransition transition) {
        if (mFullscreenTransition != FullscreenTransition.NONE) {
            return false;
        }
        mFullscreenTransition = transition;
        return true;
    }

    synchronized boolean isFullscreenTransition() {
        return mFullscreenTransition != FullscreenTransition.NONE;
    }

    synchronized boolean isFullscreenEntryTransition() {
        return mFullscreenTransition == FullscreenTransition.ENTERING;
    }

    synchronized boolean isFullscreenRestoreTransition() {
        return mFullscreenTransition == FullscreenTransition.RESTORING;
    }

    synchronized void finishFullscreenTransition() {
        mFullscreenTransition = FullscreenTransition.NONE;
    }

    synchronized boolean hasManualImmersiveOverride() {
        return mManualImmersiveOverride;
    }

    synchronized void setManualImmersiveOverride(final boolean overridden) {
        mManualImmersiveOverride = overridden;
    }

    synchronized void setStartupWindowed(final boolean startupWindowed) {
        mStartupWindowed = startupWindowed;
    }

    synchronized boolean consumeStartupWindowed() {
        final boolean startupWindowed = mStartupWindowed;
        mStartupWindowed = false;
        return startupWindowed;
    }

    private static Rect copy(final Rect bounds) {
        if (bounds == null) {
            return null;
        }
        final Rect copy = new Rect();
        copy.left = bounds.left;
        copy.top = bounds.top;
        copy.right = bounds.right;
        copy.bottom = bounds.bottom;
        return copy;
    }
}
