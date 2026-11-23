package io.github.mekhontsev.magicdesk;

/** Platform access to optional Android desktop windowing configuration. */
public interface PlatformWindowingDriver {
    /** Optional task-local exemption from firmware limits on simultaneous windows. */
    interface FreeformMultiplicity {
        boolean canClaim(Object task) throws ReflectiveOperationException;
        String describe(Object task);
        void setExempt(Object transaction, Object token, boolean exempt)
                throws ReflectiveOperationException;
    }

    default FreeformMultiplicity freeformMultiplicity() { return null; }

    /** Read-only scale supplied with TaskInfo; NaN means unavailable, not identity. */
    default float taskScale(Object task) { return 1f; }

    /** Complete TaskInfo reader and transaction writer; a read-only scale cannot drive placement. */
    default boolean hasScaledWindows() { return false; }

    /** Whether native gestures publish their released scale in TaskInfo. */
    default boolean hasNativeScaleCommit() { return false; }

    interface TaskScalePeer {
        float read() throws ReflectiveOperationException;
        void request(float scale) throws ReflectiveOperationException;
    }

    /** Optional decoration/gesture owner, separate from the WM transaction. */
    default TaskScalePeer taskScalePeer(int taskId) throws ReflectiveOperationException {
        throw new UnsupportedOperationException("task scale synchronization unavailable");
    }

    /** Preserve explicit geometry against firmware recalculation in the bounds transaction. */
    default void prepareTaskGeometry(Object transaction, Object token, float scale)
            throws ReflectiveOperationException { }

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
