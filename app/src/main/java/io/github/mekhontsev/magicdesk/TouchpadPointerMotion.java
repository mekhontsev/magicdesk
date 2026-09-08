package io.github.mekhontsev.magicdesk;

/** Converts phone touch coordinates into relative pointer motion. */
final class TouchpadPointerMotion {
    private float mPreviousFingerX;
    private float mPreviousFingerY;
    private float mDeltaX;
    private float mDeltaY;
    private boolean mActive;

    void start(
            final float fingerX,
            final float fingerY) {
        mPreviousFingerX = fingerX;
        mPreviousFingerY = fingerY;
        mDeltaX = 0.0f;
        mDeltaY = 0.0f;
        mActive = true;
    }

    boolean move(
            final float fingerX,
            final float fingerY) {
        if (!mActive) {
            return false;
        }
        // InputReader applies pointer speed and acceleration to the virtual mouse.
        mDeltaX = fingerX - mPreviousFingerX;
        mDeltaY = fingerY - mPreviousFingerY;
        mPreviousFingerX = fingerX;
        mPreviousFingerY = fingerY;
        return true;
    }

    void stop() {
        mActive = false;
    }

    boolean isActive() {
        return mActive;
    }

    float deltaX() {
        return mDeltaX;
    }

    float deltaY() {
        return mDeltaY;
    }

}
