package io.github.mekhontsev.magicdesk;

/** Admits one workspace runtime; session policy remains outside its local lifetime. */
final class DesktopSessionRegistry {
    private DesktopWorkspaceRuntime mWorkspace;
    private DesktopSessionPolicy mPolicy = DesktopSessionPolicy.USER;

    DesktopSessionSnapshot snapshot() {
        return mWorkspace == null ? DesktopSessionSnapshot.empty()
                : DesktopSessionSnapshot.of(mWorkspace.snapshot(), mPolicy);
    }

    DesktopWorkspaceRuntime workspace() {
        return mWorkspace;
    }

    DesktopWorkspaceRuntime workspace(final int displayId) {
        return mWorkspace != null && !mWorkspace.isClosed() && mWorkspace.displayId == displayId
                ? mWorkspace : null;
    }

    void noteTarget(final DesktopDisplayTarget target) {
        noteTarget(target, DesktopSessionPolicy.USER);
    }

    void noteTarget(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (target == null) { return; }
        if (mWorkspace == null || mWorkspace.isClosed()
                || mWorkspace.snapshot().target == null && !mWorkspace.snapshot().hasHost()) {
            replaceWorkspace(target);
        } else if (mWorkspace.displayId != target.workspaceDisplayId
                || mWorkspace.snapshot().target != null
                    && !mWorkspace.snapshot().target.sameBinding(target)) {
            if (mWorkspace.snapshot().hasHost()) {
                throw new IllegalStateException("another desktop workspace is active");
            }
            replaceWorkspace(target);
        }
        mWorkspace.update(mWorkspace.snapshot().withTarget(target));
        mPolicy = policy == null ? DesktopSessionPolicy.USER : policy;
    }

    void clearTarget(final DesktopDisplayTarget target) {
        final DesktopSessionSnapshot next = snapshot().clearTarget(target);
        if (mWorkspace != null && !mWorkspace.isClosed()) { mWorkspace.update(next.workspace()); }
        mPolicy = next.policy();
    }

    boolean registerHost(
            final int displayId,
            final int taskId,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        final DesktopSessionSnapshot current = snapshot();
        if (current.hasHost()
                && (current.activeWorkspaceDisplayId() != displayId
                        || current.hostTaskId() != taskId)) {
            return false;
        }
        if (current.hasHost()) {
            return target != null
                    && target.sameBinding(current.target())
                    && current.policy() == (policy == null
                            ? DesktopSessionPolicy.USER : policy);
        }
        final DesktopDisplayTarget registeredTarget = target == null
                ? current.targetForWorkspace(displayId) : target;
        if (registeredTarget == null
                || registeredTarget.workspaceDisplayId != displayId
                || (current.target() != null
                        && !registeredTarget.sameBinding(current.target()))) {
            return false;
        }
        noteTarget(registeredTarget, policy);
        mWorkspace.update(mWorkspace.snapshot().registerHost(displayId, taskId));
        return true;
    }

    void unregisterHost(
            final int displayId,
            final boolean changingConfigurations) {
        if (mWorkspace == null || mWorkspace.isClosed()
                || mWorkspace.snapshot().hostDisplayId != displayId) {
            return;
        }
        final DesktopSessionSnapshot next = snapshot().unregisterHost(displayId, changingConfigurations);
        mWorkspace.update(next.workspace());
        mWorkspace.detachHost();
        mPolicy = next.policy();
        if (!changingConfigurations) { mWorkspace.close(); }
    }

    void close() {
        if (mWorkspace != null) { mWorkspace.close(); }
        mWorkspace = null;
        mPolicy = DesktopSessionPolicy.USER;
    }

    private void replaceWorkspace(final DesktopDisplayTarget target) {
        if (mWorkspace != null) { mWorkspace.close(); }
        mWorkspace = new DesktopWorkspaceRuntime(target);
    }

}
