package io.github.mekhontsev.magicdesk;

import java.util.concurrent.atomic.AtomicBoolean;

/** Guards overlapping desktop activation, close, and mirror transitions. */
final class DesktopTransitionGate {
    private final AtomicBoolean mStartInProgress = new AtomicBoolean();
    private final AtomicBoolean mCloseInProgress = new AtomicBoolean();

    boolean beginDesktopStart() {
        return !mCloseInProgress.get()
                && mStartInProgress.compareAndSet(false, true);
    }

    boolean beginModeTransition() {
        return mStartInProgress.compareAndSet(false, true);
    }

    void finishStart() {
        mStartInProgress.set(false);
    }

    boolean beginClose() {
        return mCloseInProgress.compareAndSet(false, true);
    }

    void finishClose() {
        mCloseInProgress.set(false);
    }

    boolean isCloseInProgress() {
        return mCloseInProgress.get();
    }
}
