package io.github.mekhontsev.magicdesk;

final class FreeformTaskCleanupPolicy {
    enum Action {
        KEEP,
        FORGET,
        REMOVE_RECENT
    }

    private FreeformTaskCleanupPolicy() {
    }

    static Action decide(
            final String expectedPackage,
            final int expectedDisplayId,
            final boolean liveTaskPresent,
            final String livePackage,
            final int liveDisplayId,
            final boolean liveTaskFreeform,
            final boolean recentTaskPresent,
            final String recentPackage,
            final int recentDisplayId) {
        if (liveTaskPresent) {
            return expectedPackage.equals(livePackage)
                    && expectedDisplayId == liveDisplayId
                    && liveTaskFreeform
                    ? Action.KEEP : Action.FORGET;
        }
        return recentTaskPresent
                && expectedPackage.equals(recentPackage)
                && expectedDisplayId == recentDisplayId
                ? Action.REMOVE_RECENT : Action.FORGET;
    }
}
