package io.github.mekhontsev.magicdesk;

/** Ends one workspace; the last member releases the shared HOME role. */
final class DesktopSessionEndPlan {
    enum Tasks {
        RETURN_TO_DEFAULT_AND_REMEMBER,
        RETURN_TO_DEFAULT
    }

    final DesktopDisplayTarget workspace;
    final Tasks tasks;
    final DesktopCloseMode destination;
    final boolean recoverPhoneTasks;

    private DesktopSessionEndPlan(final DesktopDisplayTarget target,
            final DesktopCloseMode mode, final boolean recovery) {
        workspace = target;
        destination = mode;
        tasks = mode == DesktopCloseMode.EXIT ? Tasks.RETURN_TO_DEFAULT
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

    boolean needsPhoneRecovery() {
        return !workspace.isDefaultWorkspace();
    }
}
