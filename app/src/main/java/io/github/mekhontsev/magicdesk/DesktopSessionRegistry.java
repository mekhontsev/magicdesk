package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Display-local residencies. The UI gateway serializes access to this registry. */
final class DesktopSessionRegistry {
    private final Map<Integer, DesktopWorkspaceRuntime> mWorkspaces = new LinkedHashMap<>();

    DesktopSessionSnapshot snapshot(final int displayId) {
        final DesktopWorkspaceRuntime workspace = workspace(displayId);
        return workspace == null ? DesktopSessionSnapshot.empty()
                : DesktopSessionSnapshot.of(workspace.snapshot(), workspace.policy);
    }

    List<DesktopSessionSnapshot> snapshots() {
        return mWorkspaces.values().stream().filter(workspace -> !workspace.isClosed())
                .map(workspace -> DesktopSessionSnapshot.of(workspace.snapshot(), workspace.policy)).toList();
    }

    DesktopWorkspaceRuntime workspace(final int displayId) {
        final DesktopWorkspaceRuntime workspace = mWorkspaces.get(displayId);
        return workspace == null || workspace.isClosed() ? null : workspace;
    }

    void noteTarget(final DesktopDisplayTarget target) {
        noteTarget(target, DesktopSessionPolicy.USER);
    }

    void noteTarget(final DesktopDisplayTarget target, final DesktopSessionPolicy requestedPolicy) {
        if (target == null) { return; }
        final DesktopSessionPolicy policy = requestedPolicy == null ? DesktopSessionPolicy.USER : requestedPolicy;
        DesktopWorkspaceRuntime workspace = workspace(target.workspaceDisplayId);
        if (workspace == null) {
            if (!mWorkspaces.isEmpty() && (policy == DesktopSessionPolicy.ISOLATED_SELF_TEST
                    || mWorkspaces.values().stream().anyMatch(value -> value.policy != policy))) {
                throw new IllegalStateException("isolated sessions cannot share Desktop ownership");
            }
            workspace = new DesktopWorkspaceRuntime(target, policy);
            mWorkspaces.put(target.workspaceDisplayId, workspace);
        } else if (workspace.policy != policy
                || workspace.snapshot().target != null && !workspace.snapshot().target.sameBinding(target)) {
            throw new IllegalStateException("workspace binding or policy changed without closing it");
        }
        workspace.update(workspace.snapshot().withTarget(target));
    }

    void clearTarget(final DesktopDisplayTarget target) {
        if (target == null) { return; }
        final DesktopWorkspaceRuntime workspace = workspace(target.workspaceDisplayId);
        if (workspace == null || workspace.snapshot().target == null
                || !workspace.snapshot().target.sameBinding(target)) { return; }
        close(target.workspaceDisplayId);
    }

    boolean registerHost(final int displayId, final int taskId,
            final DesktopDisplayTarget target, final DesktopSessionPolicy requestedPolicy) {
        final DesktopSessionSnapshot current = snapshot(displayId);
        final DesktopSessionPolicy policy = requestedPolicy == null ? DesktopSessionPolicy.USER : requestedPolicy;
        if (current.hasHost()) {
            return current.hostTaskId() == taskId && target != null
                    && target.sameBinding(current.target()) && current.policy() == policy;
        }
        final DesktopDisplayTarget admitted = target == null ? current.target() : target;
        if (admitted == null || !admitted.ownsWorkspace(displayId)
                || current.target() != null && !current.target().sameBinding(admitted)) { return false; }
        noteTarget(admitted, policy);
        final DesktopWorkspaceRuntime workspace = workspace(displayId);
        workspace.update(workspace.snapshot().registerHost(displayId, taskId));
        return true;
    }

    void unregisterHost(final int displayId, final boolean changingConfigurations) {
        final DesktopWorkspaceRuntime workspace = workspace(displayId);
        if (workspace == null || workspace.snapshot().hostDisplayId != displayId) { return; }
        workspace.update(workspace.snapshot().unregisterHost(displayId, changingConfigurations));
        workspace.detachHost();
        if (!changingConfigurations) { close(displayId); }
    }

    void close(final int displayId) {
        final DesktopWorkspaceRuntime workspace = mWorkspaces.remove(displayId);
        if (workspace != null) { workspace.close(); }
    }
}
