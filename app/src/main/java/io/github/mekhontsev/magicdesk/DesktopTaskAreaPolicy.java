package io.github.mekhontsev.magicdesk;

/** Chooses which task display area owns windows on one desktop target. */
enum DesktopTaskAreaPolicy {
    DEFAULT(0),
    SESSION(1),
    INDEPENDENT(2);

    private final int mWireValue;

    DesktopTaskAreaPolicy(final int wireValue) {
        mWireValue = wireValue;
    }

    int wireValue() {
        return mWireValue;
    }

    static DesktopTaskAreaPolicy fromWireValue(final int wireValue) {
        for (final DesktopTaskAreaPolicy policy : values()) {
            if (policy.mWireValue == wireValue) {
                return policy;
            }
        }
        throw new IllegalArgumentException(
                "unknown desktop task-area policy " + wireValue);
    }

    /** Whether one organizer area owns the desktop host and freeform roots. */
    boolean usesManagedWorkspaceArea() {
        return this != DEFAULT;
    }

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
        return this != SESSION;
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
