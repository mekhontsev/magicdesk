package io.github.mekhontsev.magicdesk;

/** Pure policy for task moves observed after Android has selected a display. */
final class ExternalTaskMigrationPolicy {
    private static final int PHONE_DISPLAY_ID = 0;

    private ExternalTaskMigrationPolicy() {
    }

    static boolean shouldNormalizeObservedTask(
            final int displayId,
            final boolean enabled,
            final boolean freeformTask) {
        return enabled
                && freeformTask
                && displayId == PHONE_DISPLAY_ID;
    }
}
