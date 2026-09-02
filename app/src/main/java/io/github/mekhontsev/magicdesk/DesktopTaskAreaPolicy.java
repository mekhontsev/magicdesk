package io.github.mekhontsev.magicdesk;

/** Chooses which task display area owns windows on one desktop target. */
enum DesktopTaskAreaPolicy {
    UNCONFIGURED(0),
    // HOME stays in Android's default area; application tasks share one
    // organizer area so their internal ordering is isolated from Recents.
    SESSION(1),
    // HOME and freeform tasks use the display root; fullscreen tasks receive
    // independent organizer planes.
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

    /** Whether application tasks share one organizer-owned application area. */
    boolean usesManagedApplicationArea() {
        return this == SESSION;
    }

    /** Whether application tasks remain direct children of the display root. */
    boolean usesDirectRootWorkspace() {
        return this == INDEPENDENT;
    }

    /** Whether application immersive tasks retain that application parent. */
    boolean usesSessionFullscreenHierarchy() {
        return this == SESSION;
    }

    /** Whether each fullscreen task needs an independently reordered plane. */
    boolean usesIndependentFullscreenPlanes() {
        return this == INDEPENDENT;
    }
}
