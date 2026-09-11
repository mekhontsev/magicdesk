package io.github.mekhontsev.magicdesk;

/** Ends the entire session, restoring system input; not a workspace/output handoff. */
final class DesktopSessionEndPlan {
    enum Tasks {
        RETURN_TO_DEFAULT_AND_REMEMBER,
        ALREADY_RETURNED
    }

    final DesktopDisplayTarget workspace;
    final Tasks tasks;
    final DesktopCloseMode destination;
    final boolean recoverPhoneTasks;

    private DesktopSessionEndPlan(final DesktopDisplayTarget target,
            final DesktopCloseMode mode, final boolean recovery) {
        workspace = target;
        destination = mode;
        // Exit's preceding task-return step has already cleared preservation.
        tasks = mode == DesktopCloseMode.EXIT ? Tasks.ALREADY_RETURNED
                : Tasks.RETURN_TO_DEFAULT_AND_REMEMBER;
        recoverPhoneTasks = recovery;
    }

    static DesktopSessionEndPlan create(final DesktopWorkspaceSnapshot current,
            final DesktopDisplayTarget requested, final DesktopCloseMode mode,
            final boolean recovery) {
        if (requested == null || mode == null || current == null) {
            throw new IllegalArgumentException("session end requires a workspace and destination");
        }
        if ((current.target != null && !current.target.sameBinding(requested))
                || (current.hasHost() && current.hostDisplayId != requested.workspaceDisplayId)) {
            throw new IllegalStateException("close target does not own the current workspace");
        }
        // A vanished host is still eligible for cleanup through its retained target.
        return new DesktopSessionEndPlan(requested, mode, recovery);
    }

    boolean returnsTasks() {
        return tasks == Tasks.RETURN_TO_DEFAULT_AND_REMEMBER;
    }

    boolean needsPhoneRecovery() {
        return returnsTasks() && !workspace.isDefaultWorkspace();
    }
}
