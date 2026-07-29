package io.github.mekhontsev.magicdesk;

final class ActivityRoleResolver {
    private ActivityRoleResolver() {
    }

    static boolean opensPhoneControl(
            final SessionProfile.DisplayTarget target,
            final int currentDisplayId) {
        return currentDisplayId == 0
                && target == SessionProfile.DisplayTarget.AUTO;
    }
}
