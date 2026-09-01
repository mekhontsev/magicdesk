package io.github.mekhontsev.magicdesk;

/** Converts phone touch coordinates into relative pointer motion. */
final class TouchpadPointerMotion {
    private float mPreviousFingerX;
    private float mPreviousFingerY;
    private float mSensitivity = 1.0f;
    private float mDeltaX;
    private float mDeltaY;
    private boolean mActive;

    void start(
            final float fingerX,
            final float fingerY,
            final float sensitivity) {
        mPreviousFingerX = fingerX;
        mPreviousFingerY = fingerY;
        mDeltaX = 0.0f;
        mDeltaY = 0.0f;
        mSensitivity = Math.max(0.1f, sensitivity);
        mActive = true;
    }

    boolean move(
            final float fingerX,
            final float fingerY) {
        if (!mActive) {
            return false;
        }
        mDeltaX = (fingerX - mPreviousFingerX) * mSensitivity;
        mDeltaY = (fingerY - mPreviousFingerY) * mSensitivity;
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
