package io.github.mekhontsev.magicdesk;

/** Chooses which task display area owns windows on one desktop target. */
enum DesktopTaskAreaPolicy {
    DEFAULT,
    SESSION;

    /** Whether application immersive tasks must retain the session parent. */
    boolean usesSessionFullscreenHierarchy() {
        return this == SESSION;
    }
}
