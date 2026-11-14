package io.github.mekhontsev.magicdesk;

/** Platform access to optional Android desktop windowing configuration. */
public interface PlatformWindowingDriver {
    /** Read-only scale supplied with TaskInfo; NaN means unavailable, not identity. */
    default float taskScale(Object task) { return 1f; }

    /** Optional firmware geometry, independent of managed Desktop ownership. */
    default boolean hasScaledWindows() { return false; }

    /** Preserve explicit geometry against firmware recalculation in the bounds transaction. */
    default void prepareTaskGeometry(Object transaction, Class<?> tokenClass,
            Object token, float scale) throws ReflectiveOperationException { }

    default String geometryDiagnostic() { return "identity"; }

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
