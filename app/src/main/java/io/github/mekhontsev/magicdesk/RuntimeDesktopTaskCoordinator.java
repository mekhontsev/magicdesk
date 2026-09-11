package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.view.Display;

import java.util.function.IntConsumer;

/** Owns task observation and the display-scoped desktop task controller. */
final class RuntimeDesktopTaskCoordinator {
    enum Mode {
        DISABLED,
        OBSERVING,
        ACTIVE
    }

    private final DesktopTaskController mTasks;
    private final DesktopTaskParkingController mParking =
            new DesktopTaskParkingController();

    private Mode mMode = Mode.DISABLED;
    private boolean mDestroyed;
    private int mPreparedHostTaskId = -1;
    private DesktopWorkspaceRuntime mWorkspace;

    RuntimeDesktopTaskCoordinator(
            final Context context,
            final Handler handler,
            final Runnable taskStackChanged,
            final IntConsumer desktopPrepared) {
        mTasks = new DesktopTaskController(
                context,
                handler,
                taskStackChanged,
                (displayId, tasks, workArea, ownershipReady, ownedTaskIds) -> {
                    mParking.observe(displayId, tasks, workArea,
                            ownershipReady, ownedTaskIds);
                    final DesktopSessionSnapshot session =
                            DesktopRuntimeBridge.getSessionSnapshot();
                    final int hostTaskId = session.hostTaskId();
                    if (hostTaskId >= 0 && hostTaskId != mPreparedHostTaskId
                            && mWorkspace != null && !mWorkspace.isClosed()
                            && mWorkspace == DesktopRuntimeBridge.getWorkspaceRuntime(displayId)
                            && session.activeWorkspaceDisplayId() == displayId
                            && ownershipReady
                            && DesktopRuntimeBridge.isDesktopReadyOnDisplay(displayId)
                            && mParking.isWorkspacePrepared(session)) {
                        mPreparedHostTaskId = hostTaskId;
                        desktopPrepared.accept(displayId);
                    }
                });
    }

    void reconcile(
            final DesktopSessionSnapshot session,
            final boolean shellReady) {
        if (mDestroyed) {
            return;
        }
        final Mode mode = modeFor(session, shellReady);
        final int displayId = mode == Mode.ACTIVE
                ? session.activeWorkspaceDisplayId() : Display.INVALID_DISPLAY;

        if (mode == Mode.ACTIVE) {
            final DesktopWorkspaceRuntime workspace =
                    DesktopRuntimeBridge.getWorkspaceRuntime(displayId);
            if (workspace == null || workspace.isClosed()) {
                return;
            }
            if (mWorkspace != workspace) {
                mPreparedHostTaskId = -1;
                mWorkspace = workspace;
            }
            mTasks.setTaskWatcherEnabled(true);
            // start() refreshes the current display when it is already active.
            mTasks.start(displayId);
        } else {
            mPreparedHostTaskId = -1;
            if (mMode == Mode.ACTIVE) {
                mTasks.stop();
            }
            mTasks.setTaskWatcherEnabled(mode == Mode.OBSERVING);
        }
        mMode = mode;
    }

    void destroy() {
        if (mDestroyed) {
            return;
        }
        mDestroyed = true;
        mWorkspace = null;
        mMode = Mode.DISABLED;
        mPreparedHostTaskId = -1;
        mParking.clear();
        mTasks.destroy();
    }

    void releaseSession(final Runnable completion) {
        mWorkspace = null;
        mPreparedHostTaskId = -1;
        if (mDestroyed || mMode == Mode.DISABLED) {
            completion.run();
            return;
        }
        mMode = Mode.DISABLED;
        mTasks.stop();
        mTasks.setTaskWatcherEnabled(false, completion);
    }

    void releaseWorkspace(final DesktopWorkspaceRuntime workspace, final Runnable completion) {
        // Admission can precede task-controller activation. Protect both owners
        // when an old host finishes after another workspace has been prepared.
        final DesktopDisplayTarget target = DesktopRuntimeBridge.getActiveDesktopTarget();
        final DesktopWorkspaceRuntime admitted = target == null ? null
                : DesktopRuntimeBridge.getWorkspaceRuntime(target.workspaceDisplayId);
        if (!canReleaseWorkspace(workspace, mWorkspace, admitted)) {
            completion.run();
            return;
        }
        releaseSession(completion);
    }

    static boolean canReleaseWorkspace(final DesktopWorkspaceRuntime requested,
            final DesktopWorkspaceRuntime active, final DesktopWorkspaceRuntime admitted) {
        return requested != null && (active == null || active == requested)
                && (admitted == null || admitted == requested);
    }

    DesktopTaskRuntime operations() {
        return mTasks;
    }

    DesktopTaskParkingRuntime parking() {
        return mParking;
    }

    static Mode modeFor(
            final DesktopSessionSnapshot session,
            final boolean shellReady) {
        if (!shellReady) {
            return Mode.DISABLED;
        }
        return session != null && session.hasHost()
                ? Mode.ACTIVE : Mode.OBSERVING;
    }
}
