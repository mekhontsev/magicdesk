package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.view.Display;

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

    RuntimeDesktopTaskCoordinator(
            final Context context,
            final Handler handler,
            final PlatformWindowingDriver windowing,
            final PlatformPhoneUiDriver phoneUi,
            final Runnable taskStackChanged) {
        mTasks = new DesktopTaskController(
                context, handler, taskStackChanged, windowing, phoneUi);
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
        mParking.clear();
        mTasks.destroy();
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
