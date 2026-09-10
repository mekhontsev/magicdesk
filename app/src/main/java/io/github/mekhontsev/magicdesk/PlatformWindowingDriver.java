package io.github.mekhontsev.magicdesk;

/** Optional firmware-specific desktop windowing configuration. */
public interface PlatformWindowingDriver {
    String restrictionsPropertyKey();

    String roundedCornersPropertyKey();

    /** Returns whether a verified property change requires a restart. */
    boolean configure(
            boolean restrictionsDisabled,
            boolean roundedCornersDisabled);

    void restoreDefaults();
}
