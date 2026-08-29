package io.github.mekhontsev.magicdesk;

/** Chooses which task display area owns windows on one desktop target. */
enum DesktopTaskAreaPolicy {
    UNCONFIGURED(0),
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

    /** Whether MagicDesk owns the desktop host's organizer area. */
    boolean usesManagedHostArea() {
        return this == SESSION;
    }

    /** Whether application tasks share the organizer-owned session area. */
    boolean usesManagedApplicationArea() {
        return this == SESSION;
    }

    /** Whether application tasks remain direct children of the display root. */
    boolean usesDirectRootWorkspace() {
        return this == INDEPENDENT;
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
        return this == INDEPENDENT;
    }
}
