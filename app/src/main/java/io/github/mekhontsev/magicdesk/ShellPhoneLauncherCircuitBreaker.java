package io.github.mekhontsev.magicdesk;

/** Isolates a crashing phone launcher for the remainder of one desktop session. */
final class ShellPhoneLauncherCircuitBreaker {
    private final boolean mEnabled;

    private volatile boolean mSessionActive;
    private volatile boolean mTripped;

    ShellPhoneLauncherCircuitBreaker(final boolean enabled) {
        mEnabled = enabled;
    }

    synchronized void configure(final boolean sessionActive) {
        if (mSessionActive == sessionActive) {
            return;
        }
        mSessionActive = sessionActive;
        mTripped = false;
    }

    synchronized boolean noteLauncherFailure(final int type) {
        if (!mEnabled
                || !mSessionActive
                || mTripped
                || type != PhoneLauncherEvent.CRASH) {
            return false;
        }
        mTripped = true;
        return true;
    }

    boolean allowActivityStart(final boolean primaryHomeStart) {
        return !primaryHomeStart || !mTripped;
    }

    boolean isTripped() {
        return mTripped;
    }
}
