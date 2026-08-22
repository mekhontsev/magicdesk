package io.github.mekhontsev.magicdesk;

/** Pure state machine for preserving a task mode across activity handoffs. */
final class TaskActivityModeState {
    enum Decision {
        NONE,
        RESTORE_FREEFORM,
        RESTORE_FULLSCREEN,
        SETTLED,
        ALLOW_IMMERSIVE
    }

    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final String mRootPackage;
    private final int mPreferredWindowingMode;

    private String mExpectedComponent;
    private String mExpectedPackage;
    private boolean mArmed;
    private boolean mCorrectionInFlight;

    TaskActivityModeState(
            final String rootPackage,
            final int preferredWindowingMode) {
        if (!PackageNameValidator.isSafe(rootPackage)) {
            throw new IllegalArgumentException("invalid task package");
        }
        if (preferredWindowingMode != WINDOWING_MODE_FULLSCREEN
                && preferredWindowingMode != WINDOWING_MODE_FREEFORM) {
            throw new IllegalArgumentException("invalid preferred task mode");
        }
        mRootPackage = rootPackage;
        mPreferredWindowingMode = preferredWindowingMode;
    }

    String rootPackage() {
        return mRootPackage;
    }

    void arm(
            final String expectedComponent,
            final String expectedPackage) {
        mExpectedComponent = expectedComponent;
        mExpectedPackage = expectedPackage;
        mArmed = true;
    }

    Decision observe(
            final String topComponent,
            final String topPackage,
            final int windowingMode,
            final boolean requestingImmersive) {
        if (!mArmed) {
            return Decision.NONE;
        }
        final boolean expectedActivityVisible = matchesExpected(
                topComponent, topPackage);
        if (windowingMode == mPreferredWindowingMode) {
            mCorrectionInFlight = false;
            if (expectedActivityVisible) {
                clear();
                return Decision.SETTLED;
            }
            return Decision.NONE;
        }
        if (mPreferredWindowingMode == WINDOWING_MODE_FULLSCREEN) {
            if (windowingMode != WINDOWING_MODE_FREEFORM
                    || !expectedActivityVisible) {
                return Decision.NONE;
            }
            if (mCorrectionInFlight) {
                return Decision.NONE;
            }
            mCorrectionInFlight = true;
            return Decision.RESTORE_FULLSCREEN;
        }
        if (windowingMode != WINDOWING_MODE_FULLSCREEN
                || (!expectedActivityVisible
                        && mRootPackage.equals(topPackage))) {
            return Decision.NONE;
        }
        if (requestingImmersive && expectedActivityVisible) {
            clear();
            return Decision.ALLOW_IMMERSIVE;
        }
        if (mCorrectionInFlight) {
            return Decision.NONE;
        }
        mCorrectionInFlight = true;
        return Decision.RESTORE_FREEFORM;
    }

    void correctionFailed() {
        mCorrectionInFlight = false;
    }

    void correctionApplied() {
        clear();
    }

    boolean isArmed() {
        return mArmed;
    }

    private boolean matchesExpected(
            final String topComponent,
            final String topPackage) {
        if (mExpectedComponent != null) {
            return mExpectedComponent.equals(topComponent);
        }
        return mExpectedPackage != null
                && mExpectedPackage.equals(topPackage);
    }

    private void clear() {
        mExpectedComponent = null;
        mExpectedPackage = null;
        mArmed = false;
        mCorrectionInFlight = false;
    }
}
