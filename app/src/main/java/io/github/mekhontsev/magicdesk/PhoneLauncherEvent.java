package io.github.mekhontsev.magicdesk;

/** Event types emitted for the primary phone launcher during a desktop session. */
final class PhoneLauncherEvent {
    static final int HOME_START_ALLOWED = 1;
    static final int HOME_START_BLOCKED = 2;
    static final int HOME_RESUME_BLOCKED = 3;
    static final int ANR = 4;
    static final int PROCESS_DIED = 5;

    private PhoneLauncherEvent() {
    }

    static boolean isFailure(final int type) {
        return type == ANR || type == PROCESS_DIED;
    }

    static String label(final int type) {
        if (type == HOME_START_ALLOWED) {
            return "home-start-allowed";
        }
        if (type == HOME_START_BLOCKED) {
            return "home-start-blocked";
        }
        if (type == HOME_RESUME_BLOCKED) {
            return "home-resume-blocked";
        }
        if (type == ANR) {
            return "launcher-anr";
        }
        if (type == PROCESS_DIED) {
            return "launcher-process-died";
        }
        return "unknown";
    }
}
