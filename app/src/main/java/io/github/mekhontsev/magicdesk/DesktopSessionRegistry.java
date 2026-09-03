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

    void noteTarget(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        mSnapshot = mSnapshot.noteTarget(target, policy);
    }

    void clearTarget(final DesktopDisplayTarget target) {
        mSnapshot = mSnapshot.clearTarget(target);
    }

    boolean registerHost(
            final int displayId,
            final int taskId,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (mSnapshot.hasHost()
                && (mSnapshot.activeDisplayId() != displayId
                        || mSnapshot.hostTaskId() != taskId)) {
            return false;
        }
        if (mSnapshot.hasHost()) {
            return target != null
                    && sameTarget(mSnapshot.target(), target)
                    && mSnapshot.policy() == (policy == null
                            ? DesktopSessionPolicy.USER : policy);
        }
        final DesktopDisplayTarget registeredTarget = target == null
                ? mSnapshot.targetForDisplay(displayId) : target;
        if (registeredTarget == null
                || registeredTarget.displayId != displayId
                || (mSnapshot.target() != null
                        && !sameTarget(
                                mSnapshot.target(), registeredTarget))) {
            return false;
        }
        mSnapshot = mSnapshot.noteTarget(registeredTarget, policy);
        mSnapshot = mSnapshot.registerHost(displayId, taskId);
        return true;
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

    private static boolean sameTarget(
            final DesktopDisplayTarget first,
            final DesktopDisplayTarget second) {
        return first != null
                && second != null
                && first.displayId == second.displayId
                && first.kind == second.kind;
    }
}
