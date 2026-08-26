package io.github.mekhontsev.magicdesk;

/** Chooses which task display area owns windows on one desktop target. */
enum DesktopTaskAreaPolicy {
    DEFAULT,
    SESSION;

    /** Whether application immersive tasks must retain the session parent. */
    boolean usesSessionFullscreenHierarchy() {
        return this == SESSION;
    }

    /** Whether tasks already live in one session-owned task display area. */
    boolean usesSessionParent() {
        return this == SESSION;
    }

    /** Whether each fullscreen task needs an independently reordered plane. */
    boolean usesIndependentFullscreenPlanes() {
        return this == DEFAULT;
    }

    /** Number of fullscreen application tasks that require a shared parent. */
    int minimumFullscreenTasksForSharedArea() {
        return 2;
    }

    /** Whether an empty sibling area needs a structural HOME child. */
    boolean requiresFullscreenBackstop() {
        return this == SESSION;
    }
}
