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

    RuntimeDesktopTaskCoordinator(
            final Context context,
            final Handler handler,
            final PlatformWindowingDriver windowing,
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
                            && session.activeDisplayId() == displayId
                            && ownershipReady
                            && DesktopRuntimeBridge.isDesktopReadyOnDisplay(displayId)
                            && mParking.isWorkspacePrepared(session)) {
                        mPreparedHostTaskId = hostTaskId;
                        desktopPrepared.accept(displayId);
                    }
                },
                windowing);
    }

    void reconcile(
            final DesktopSessionSnapshot session,
            final boolean shellReady) {
        if (mDestroyed) {
            return;
        }
        final Mode mode = modeFor(session, shellReady);
        final int displayId = mode == Mode.ACTIVE
                ? session.activeDisplayId() : Display.INVALID_DISPLAY;

        if (mode == Mode.ACTIVE) {
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
        mMode = Mode.DISABLED;
        mPreparedHostTaskId = -1;
        mParking.clear();
        mTasks.destroy();
    }

    void releaseSession(final Runnable completion) {
        mPreparedHostTaskId = -1;
        if (mDestroyed || mMode == Mode.DISABLED) {
            completion.run();
            return;
        }
        mMode = Mode.DISABLED;
        mTasks.stop();
        mTasks.setTaskWatcherEnabled(false, completion);
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
