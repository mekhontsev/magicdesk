package io.github.mekhontsev.magicdesk;

/** Display-local target and host lifetime, independent of session-wide policy. */
final class DesktopWorkspaceSnapshot {
    final String id;
    final DesktopDisplayTarget target;
    final int hostDisplayId;
    final int hostTaskId;

    private DesktopWorkspaceSnapshot(final String id, final DesktopDisplayTarget target,
            final int hostDisplayId, final int hostTaskId) {
        this.id = id;
        this.target = target;
        this.hostDisplayId = hostDisplayId;
        this.hostTaskId = hostTaskId;
    }

    static DesktopWorkspaceSnapshot empty() {
        return new DesktopWorkspaceSnapshot("", null, -1, -1);
    }

    static DesktopWorkspaceSnapshot admitted(final String id, final DesktopDisplayTarget target) {
        return new DesktopWorkspaceSnapshot(id, target, -1, -1);
    }

    boolean hasHost() {
        return hostDisplayId >= 0;
    }

    boolean ownsDisplay(final int displayId) {
        return displayId >= 0 && (hostDisplayId == displayId
                || (target != null && target.ownsWorkspace(displayId)));
    }

    DesktopWorkspaceSnapshot withTarget(final DesktopDisplayTarget value) {
        // A failed start can clear admission before the host's final callback.
        return new DesktopWorkspaceSnapshot(id, value, hostDisplayId, hostTaskId);
    }

    DesktopWorkspaceSnapshot registerHost(final int displayId, final int taskId) {
        if (target == null || !target.ownsWorkspace(displayId)) {
            throw new IllegalStateException("desktop host does not match the prepared target");
        }
        return new DesktopWorkspaceSnapshot(id, target, displayId, taskId);
    }

    DesktopWorkspaceSnapshot unregisterHost(final int displayId,
            final boolean changingConfigurations) {
        final boolean clearTarget = !changingConfigurations && target != null
                && (target.ownsWorkspace(displayId) || target.isDefaultWorkspace());
        return new DesktopWorkspaceSnapshot(id, clearTarget ? null : target, -1, -1);
    }
}
