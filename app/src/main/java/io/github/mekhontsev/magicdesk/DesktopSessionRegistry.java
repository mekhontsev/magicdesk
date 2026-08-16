package io.github.mekhontsev.magicdesk;

/** Owns the process-local immutable desktop session snapshot. */
final class DesktopSessionRegistry {
    private DesktopSessionSnapshot mSnapshot = DesktopSessionSnapshot.empty();

    DesktopSessionSnapshot snapshot() {
        return mSnapshot;
    }

    void noteTarget(final DesktopDisplayTarget target) {
        mSnapshot = mSnapshot.noteTarget(target);
    }

    void clearTarget(final DesktopDisplayTarget target) {
        mSnapshot = mSnapshot.clearTarget(target);
    }

    void registerHost(
            final int displayId,
            final int taskId,
            final boolean replacingSameTask) {
        mSnapshot = mSnapshot.registerHost(
                displayId, taskId, replacingSameTask);
    }

    void observeHost(final int displayId, final int taskId) {
        mSnapshot = mSnapshot.observeHost(displayId, taskId);
    }

    void unregisterHost(
            final int displayId,
            final boolean changingConfigurations) {
        mSnapshot = mSnapshot.unregisterHost(
                displayId, changingConfigurations);
    }

    void close() {
        mSnapshot = mSnapshot.close();
    }
}
