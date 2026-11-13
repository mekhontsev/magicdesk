package io.github.mekhontsev.magicdesk;

/** Platform access to optional Android desktop windowing configuration. */
public interface PlatformWindowingDriver {
    /** Whether ordinary application windows may outlive any managed Desktop. */
    default boolean supportsIndependentFreeform() { return true; }

    String restrictionsPropertyKey();

    String roundedCornersPropertyKey();

    default boolean canChangeDeviceRestrictions() { return false; }

    default boolean deviceRestrictionsEnabled() throws java.io.IOException {
        throw new java.io.IOException("desktop device restrictions are not configurable");
    }

    /** Explicit user action: unlike optional setup, a rejected write must propagate. */
    default void setDeviceRestrictionsEnabled(boolean enabled) throws java.io.IOException {
        throw new java.io.IOException("desktop device restrictions are not configurable");
    }

    /** Returns whether a verified property change requires a restart. */
    boolean configure(
            boolean restrictionsDisabled,
            boolean roundedCornersDisabled);

    void restoreDefaults();
}
