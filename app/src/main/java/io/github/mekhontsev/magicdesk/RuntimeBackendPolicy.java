package io.github.mekhontsev.magicdesk;

final class RuntimeBackendPolicy {
    private RuntimeBackendPolicy() {
    }

    static boolean shouldProbeRoot(
            final SessionProfile.PrivilegeMode requestedMode) {
        final SessionProfile.PrivilegeMode mode = normalize(requestedMode);
        return mode == SessionProfile.PrivilegeMode.AUTO
                || mode == SessionProfile.PrivilegeMode.ROOT;
    }

    static boolean shouldProbeShizuku(
            final SessionProfile.PrivilegeMode requestedMode) {
        return normalize(requestedMode)
                == SessionProfile.PrivilegeMode.SHIZUKU;
    }

    static RuntimeAccess.Backend select(
            final SessionProfile.PrivilegeMode requestedMode,
            final boolean rootAvailable,
            final boolean shizukuReady,
            final int shizukuUid) {
        switch (normalize(requestedMode)) {
            case ROOT:
            case AUTO:
                return rootAvailable
                        ? RuntimeAccess.Backend.ROOT
                        : RuntimeAccess.Backend.BASIC;
            case SHIZUKU:
                if (!shizukuReady) {
                    return RuntimeAccess.Backend.BASIC;
                }
                return shizukuUid == 0
                        ? RuntimeAccess.Backend.SHIZUKU_ROOT
                        : RuntimeAccess.Backend.SHIZUKU_SHELL;
            case BASIC:
            default:
                return RuntimeAccess.Backend.BASIC;
        }
    }

    private static SessionProfile.PrivilegeMode normalize(
            final SessionProfile.PrivilegeMode mode) {
        return mode == null ? SessionProfile.PrivilegeMode.AUTO : mode;
    }
}
