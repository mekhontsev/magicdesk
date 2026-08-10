package io.github.mekhontsev.magicdesk;

/** Converts phone touch coordinates into stable absolute pointer positions. */
final class TouchpadPointerMotion {
    private static final double MOVE_SPEED_2X = 1_500.0;
    private static final double MOVE_SPEED_4X = 2_500.0;
    private static final double MOVE_SPEED_6X = 3_500.0;

    private float mFingerAnchorX;
    private float mFingerAnchorY;
    private float mPointerAnchorX;
    private float mPointerAnchorY;
    private float mSensitivity = 1.0f;
    private int mMaximumX;
    private int mMaximumY;
    private int mOutputX;
    private int mOutputY;
    private int mVelocityScale = 1;
    private boolean mActive;

    void start(
            final float fingerX,
            final float fingerY,
            final int pointerX,
            final int pointerY,
            final int maximumX,
            final int maximumY,
            final float sensitivity) {
        mFingerAnchorX = fingerX;
        mFingerAnchorY = fingerY;
        mMaximumX = Math.max(0, maximumX);
        mMaximumY = Math.max(0, maximumY);
        mOutputX = clamp(pointerX, mMaximumX);
        mOutputY = clamp(pointerY, mMaximumY);
        mPointerAnchorX = mOutputX;
        mPointerAnchorY = mOutputY;
        mSensitivity = Math.max(0.1f, sensitivity);
        mVelocityScale = 1;
        mActive = true;
    }

    boolean move(
            final float fingerX,
            final float fingerY,
            final double velocity) {
        if (!mActive) {
            return false;
        }
        final float scale = mSensitivity * mVelocityScale;
        mOutputX = clamp(Math.round(
                mPointerAnchorX + (fingerX - mFingerAnchorX) * scale),
                mMaximumX);
        mOutputY = clamp(Math.round(
                mPointerAnchorY + (fingerY - mFingerAnchorY) * scale),
                mMaximumY);

        final int nextScale = velocityScale(velocity);
        if (nextScale != mVelocityScale) {
            mPointerAnchorX = mOutputX;
            mPointerAnchorY = mOutputY;
            mFingerAnchorX = fingerX;
            mFingerAnchorY = fingerY;
            mVelocityScale = nextScale;
        }
        return true;
    }

    void stop() {
        mActive = false;
    }

    boolean isActive() {
        return mActive;
    }

    int outputX() {
        return mOutputX;
    }

    int outputY() {
        return mOutputY;
    }

    static int velocityScale(final double velocity) {
        if (velocity > MOVE_SPEED_6X) {
            return 6;
        }
        if (velocity > MOVE_SPEED_4X) {
            return 4;
        }
        return velocity > MOVE_SPEED_2X ? 2 : 1;
    }

    private static int clamp(final int value, final int maximum) {
        return Math.max(0, Math.min(maximum, value));
    }
}
