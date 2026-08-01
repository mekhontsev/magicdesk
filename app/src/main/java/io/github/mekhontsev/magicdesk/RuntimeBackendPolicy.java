package io.github.mekhontsev.magicdesk;

final class RuntimeBackendPolicy {
    static final int SHELL_UID = 2000;

    private RuntimeBackendPolicy() {
    }

    static RuntimeAccess.Backend select(
            final boolean shizukuReady,
            final int shizukuUid) {
        return shizukuReady && shizukuUid == SHELL_UID
                ? RuntimeAccess.Backend.SHIZUKU
                : RuntimeAccess.Backend.UNAVAILABLE;
    }
}
