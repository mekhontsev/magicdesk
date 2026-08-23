package io.github.mekhontsev.magicdesk;

/** Event types emitted for the primary phone launcher during a desktop session. */
final class PhoneLauncherEvent {
    static final int HOME_START_ALLOWED = 1;
    static final int HOME_START_BLOCKED = 2;
    static final int CRASH = 3;
    static final int ANR = 4;

    private PhoneLauncherEvent() {
    }

    static boolean isFailure(final int type) {
        return type == CRASH || type == ANR;
    }

    static String label(final int type) {
        if (type == HOME_START_ALLOWED) {
            return "home-start-allowed";
        }
        if (type == HOME_START_BLOCKED) {
            return "home-start-blocked";
        }
        if (type == CRASH) {
            return "launcher-crash";
        }
        if (type == ANR) {
            return "launcher-anr";
        }
        return "unknown";
    }
}
