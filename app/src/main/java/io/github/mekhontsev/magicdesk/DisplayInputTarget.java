package io.github.mekhontsev.magicdesk;

/** Main-thread ownership of the single phone-attached input destination. */
final class DisplayInputTarget {
    private int mDesktop = -1;
    private boolean mPrepared;
    private boolean mManual;
    private int mSelected = -1;

    void desktop(final int displayId) {
        if (mDesktop == displayId) { return; }
        mDesktop = displayId;
        mPrepared = false;
        // A new Desktop acquires input. Closing one does not undo a later
        // manual selection of a different display.
        if (displayId >= 0) { mManual = false; }
    }

    void prepared(final int displayId) {
        if (mDesktop == displayId) { mPrepared = true; }
    }

    void select(final int displayId) {
        if (displayId >= 0 && displayId == mDesktop && !mPrepared) {
            throw new IllegalStateException("desktop input is not prepared");
        }
        mManual = true;
        mSelected = displayId;
    }

    int requestedDisplay() { return mManual ? mSelected : mDesktop; }
    int readyTarget() { return mManual ? mSelected : mPrepared ? mDesktop : -1; }
    boolean desktopShortcuts() { return mPrepared && readyTarget() >= 0 && readyTarget() == mDesktop; }

    boolean release(final int displayId) {
        final boolean selected = requestedDisplay() == displayId;
        if (mDesktop == displayId) { mPrepared = false; }
        if (selected) { select(-1); }
        return selected;
    }
}
