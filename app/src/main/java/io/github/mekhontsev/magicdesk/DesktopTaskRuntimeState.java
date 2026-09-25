package io.github.mekhontsev.magicdesk;

import android.content.pm.ActivityInfo;
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
        final boolean preservesRestoreBounds;

        BoundsTransition(
                final Rect targetBounds,
                final boolean preservesRestoreBounds) {
            mTargetBounds = copy(targetBounds);
            this.preservesRestoreBounds = preservesRestoreBounds;
        }

        Rect targetBounds() {
            return copy(mTargetBounds);
        }
    }

    static final class BoundsObservation {
        private final int mDisplayId;
        private final Rect mBounds;
        private final Rect mNativeArea;
        private final Rect mWorkArea;
        private final boolean mConfirmed;

        BoundsObservation(final int displayId, final Rect bounds,
                final Rect nativeArea, final Rect workArea) {
            mDisplayId = displayId;
            mBounds = copy(bounds);
            mNativeArea = copy(nativeArea);
            mWorkArea = copy(workArea);
            mConfirmed = true;
        }

        private BoundsObservation(final BoundsObservation source) {
            // Geometry is immutable and can be retained across a command boundary.
            mDisplayId = source.mDisplayId;
            mBounds = source.mBounds;
            mNativeArea = source.mNativeArea;
            mWorkArea = source.mWorkArea;
            mConfirmed = false;
        }

        boolean matches(final int displayId, final Rect bounds,
                final Rect nativeArea, final Rect workArea) {
            return mDisplayId == displayId && mBounds.equals(bounds)
                    && mNativeArea.equals(nativeArea) && mWorkArea.equals(workArea);
        }

        boolean confirms(final int displayId, final Rect bounds,
                final Rect nativeArea, final Rect workArea) {
            return mConfirmed && matches(displayId, bounds, nativeArea, workArea);
        }

        BoundsObservation invalidated() {
            return mConfirmed ? new BoundsObservation(this) : this;
        }
    }

    private final int mTaskId;

    private Rect mLastWindowBounds;
    private BoundsTransition mBoundsTransition;
    private BoundsObservation mBoundsObservation;
    private Rect mWindowRestoreBounds;
    private Rect mArrangedWindowBounds;
    private Rect mPendingSnapBounds;
    private Rect mFullscreenRestoreBounds;
    private Boolean mImmersiveRequested;
    private boolean mImmersiveRequestForeground;
    private int mRequestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean mAppRequestedFullscreen;
    private FullscreenTransition mFullscreenTransition =
            FullscreenTransition.NONE;
    private boolean mManualImmersiveOverride;
    private boolean mStartupWindowed;
    private long mStartupWindowedDeadlineUptimeMillis = Long.MAX_VALUE;

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

    synchronized BoundsTransition beginBoundsTransition(
            final Rect targetBounds,
            final boolean preservesRestoreBounds) {
        mBoundsTransition = new BoundsTransition(
                targetBounds, preservesRestoreBounds);
        if (mBoundsObservation != null) {
            mBoundsObservation = mBoundsObservation.invalidated();
        }
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
        mBoundsTransition = null;
        clearBoundsObservation();
    }

    synchronized BoundsObservation observeBounds(final int displayId,
            final Rect bounds, final Rect nativeArea, final Rect workArea) {
        final BoundsObservation previous = mBoundsObservation;
        if (previous == null || !previous.confirms(displayId, bounds, nativeArea, workArea)) {
            mBoundsObservation = new BoundsObservation(displayId, bounds, nativeArea, workArea);
        }
        return previous;
    }

    synchronized void clearBoundsObservation() {
        mBoundsObservation = null;
    }

    synchronized Rect windowRestoreBounds() {
        return copy(mWindowRestoreBounds);
    }

    synchronized void setWindowRestoreBounds(final Rect bounds) {
        mWindowRestoreBounds = copy(bounds);
    }

    synchronized void clearWindowRestoreBounds() {
        mWindowRestoreBounds = null;
        mArrangedWindowBounds = null;
    }

    synchronized Rect arrangedWindowBounds() {
        return copy(mArrangedWindowBounds);
    }

    synchronized void setArrangedWindowBounds(final Rect bounds) {
        mArrangedWindowBounds = copy(bounds);
    }

    synchronized Rect pendingSnapBounds() {
        return copy(mPendingSnapBounds);
    }

    synchronized void setPendingSnapBounds(final Rect bounds) {
        mPendingSnapBounds = copy(bounds);
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

    synchronized Boolean updateImmersiveObservation(
            final boolean requesting,
            final boolean foreground) {
        final Boolean previous = mImmersiveRequested;
        mImmersiveRequested = Boolean.valueOf(requesting);
        mImmersiveRequestForeground = foreground;
        return previous;
    }

    synchronized Boolean immersiveRequested() {
        return mImmersiveRequested;
    }

    synchronized void clearImmersiveRequested() {
        mImmersiveRequested = null;
        mImmersiveRequestForeground = false;
    }

    synchronized boolean isImmersiveRequested() {
        return Boolean.TRUE.equals(mImmersiveRequested);
    }

    synchronized boolean isImmersiveRequestForeground() {
        return mImmersiveRequestForeground;
    }

    synchronized void setRequestedOrientation(final int orientation) {
        mRequestedOrientation = orientation;
    }

    synchronized int requestedOrientation() {
        return mRequestedOrientation;
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
        mStartupWindowedDeadlineUptimeMillis = Long.MAX_VALUE;
    }

    synchronized void observeStartupWindowedInitialSample(
            final boolean requestingImmersive,
            final long observedUptimeMillis,
            final long settleMillis) {
        // A task survives its client's restart. Preserve the user's windowed
        // choice while that new client republishes its initial system-bar state.
        if (mManualImmersiveOverride && !mAppRequestedFullscreen) {
            mStartupWindowed = true;
        }
        if (!mStartupWindowed) {
            return;
        }
        if (requestingImmersive) {
            mStartupWindowed = false;
            mStartupWindowedDeadlineUptimeMillis = Long.MAX_VALUE;
            return;
        }
        mStartupWindowedDeadlineUptimeMillis = observedUptimeMillis
                + Math.max(0L, settleMillis);
    }

    synchronized boolean consumeStartupWindowed(
            final long observedUptimeMillis) {
        final boolean startupWindowed = mStartupWindowed
                && observedUptimeMillis
                        <= mStartupWindowedDeadlineUptimeMillis;
        mStartupWindowed = false;
        mStartupWindowedDeadlineUptimeMillis = Long.MAX_VALUE;
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
