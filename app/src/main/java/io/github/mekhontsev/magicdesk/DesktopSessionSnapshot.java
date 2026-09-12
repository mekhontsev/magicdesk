package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Immutable state of one admitted workspace and its policy. */
final class DesktopSessionSnapshot {
    private final DesktopWorkspaceSnapshot mWorkspace;
    private final DesktopSessionPolicy mPolicy;

    private DesktopSessionSnapshot(final DesktopWorkspaceSnapshot workspace,
            final DesktopSessionPolicy policy) {
        mWorkspace = workspace;
        mPolicy = policy == null ? DesktopSessionPolicy.USER : policy;
    }

    static DesktopSessionSnapshot empty() {
        return new DesktopSessionSnapshot(DesktopWorkspaceSnapshot.empty(), DesktopSessionPolicy.USER);
    }

    static DesktopSessionSnapshot of(final DesktopWorkspaceSnapshot workspace,
            final DesktopSessionPolicy policy) {
        return new DesktopSessionSnapshot(workspace, policy);
    }

    DesktopWorkspaceSnapshot workspace() {
        return mWorkspace;
    }

    DesktopDisplayTarget target() {
        return mWorkspace.target;
    }

    DesktopSessionPolicy policy() {
        return mPolicy;
    }

    DesktopDisplayTarget targetForWorkspace(final int displayId) {
        return target() != null && target().ownsWorkspace(displayId) ? target() : null;
    }

    int activeWorkspaceDisplayId() {
        return mWorkspace.hostDisplayId;
    }

    int activeOutputDisplayId() {
        return hasHost() && target() != null ? target().output.displayId : Display.INVALID_DISPLAY;
    }

    int hostTaskId() {
        return mWorkspace.hostTaskId;
    }

    boolean hasHost() {
        return mWorkspace.hasHost();
    }

    boolean isLocalActiveOrStarting() {
        return mWorkspace.ownsDisplay(Display.DEFAULT_DISPLAY);
    }

    DesktopSessionSnapshot noteTarget(final DesktopDisplayTarget target) {
        return noteTarget(target, DesktopSessionPolicy.USER);
    }

    DesktopSessionSnapshot noteTarget(final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        return target == null ? this
                : new DesktopSessionSnapshot(mWorkspace.withTarget(target), policy);
    }

    DesktopSessionSnapshot clearTarget(final DesktopDisplayTarget target) {
        if (target() == null || !target().sameBinding(target)) {
            return this;
        }
        return new DesktopSessionSnapshot(mWorkspace.withTarget(null), DesktopSessionPolicy.USER);
    }

    DesktopSessionSnapshot registerHost(final int displayId, final int taskId) {
        return new DesktopSessionSnapshot(mWorkspace.registerHost(displayId, taskId), mPolicy);
    }

    DesktopSessionSnapshot unregisterHost(final int displayId,
            final boolean changingConfigurations) {
        final DesktopWorkspaceSnapshot remaining =
                mWorkspace.unregisterHost(displayId, changingConfigurations);
        return new DesktopSessionSnapshot(remaining,
                remaining.target == null ? DesktopSessionPolicy.USER : mPolicy);
    }

    DesktopSessionSnapshot close() {
        return empty();
    }
}
