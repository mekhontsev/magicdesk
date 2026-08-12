package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Keeps task and input focus synchronized on a secondary desktop display. */
final class ShellDesktopFocusController implements AutoCloseable {
    interface Listener {
        void onInputFocusRefreshRequired();
    }

    private static final String TAG = "MagicDeskFocus";
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final Object mTaskService;
    private final Listener mListener;
    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskDesktopFocus");
                thread.setDaemon(true);
                return thread;
            });

    private final Object mPendingLock = new Object();

    private DisplayWindowingAccess mWindowingAccess;
    private int mDisplayId = Display.INVALID_DISPLAY;
    private int mOriginalWindowingMode = -1;
    private int mPendingFocusedTaskId = -1;
    private boolean mDrainScheduled;
    private boolean mAcceptingEvents = true;
    private boolean mAvailable;

    ShellDesktopFocusController(
            final Object taskService,
            final boolean enabled,
            final Listener listener) {
        mTaskService = taskService;
        mListener = listener;
        mAvailable = enabled;
    }

    void configure(final int displayId) {
        call(() -> {
            configureOnWorker(displayId);
            return null;
        });
    }

    void onTaskFocusChanged(final int taskId, final boolean focused) {
        if (!focused || taskId < 0) {
            return;
        }
        synchronized (mPendingLock) {
            if (!mAcceptingEvents) {
                return;
            }
            mPendingFocusedTaskId = taskId;
            if (mDrainScheduled) {
                return;
            }
            mDrainScheduled = true;
        }
        mExecutor.execute(this::drainFocusChanges);
    }

    @Override
    public void close() {
        synchronized (mPendingLock) {
            if (!mAcceptingEvents) {
                return;
            }
            mAcceptingEvents = false;
            mPendingFocusedTaskId = -1;
        }
        try {
            call(() -> {
                clearConfigurationOnWorker();
                return null;
            });
        } finally {
            mExecutor.shutdownNow();
        }
    }

    private void configureOnWorker(final int displayId) {
        final int secondaryDisplayId = mAvailable
                && displayId > Display.DEFAULT_DISPLAY
                ? displayId : Display.INVALID_DISPLAY;
        if (mDisplayId == secondaryDisplayId) {
            return;
        }
        clearConfigurationOnWorker();
        if (secondaryDisplayId == Display.INVALID_DISPLAY) {
            return;
        }
        try {
            final DisplayWindowingAccess access = windowingAccess();
            final int originalMode = access.get(secondaryDisplayId);
            mDisplayId = secondaryDisplayId;
            mOriginalWindowingMode = originalMode;
            if (originalMode != WINDOWING_MODE_FREEFORM) {
                access.set(secondaryDisplayId, WINDOWING_MODE_FREEFORM);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            final int failedDisplayId = mDisplayId;
            final int originalMode = mOriginalWindowingMode;
            mDisplayId = Display.INVALID_DISPLAY;
            mOriginalWindowingMode = -1;
            mAvailable = false;
            if (failedDisplayId != Display.INVALID_DISPLAY
                    && originalMode >= 0) {
                try {
                    windowingAccess().set(failedDisplayId, originalMode);
                } catch (ReflectiveOperationException
                        | RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            // Focus synchronization is a firmware workaround. Losing it must
            // not disable the task observer on an otherwise usable device.
            Log.w(TAG, "desktop input focus synchronization unavailable", error);
        }
    }

    private void clearConfigurationOnWorker() {
        final int displayId = mDisplayId;
        final int originalMode = mOriginalWindowingMode;
        mDisplayId = Display.INVALID_DISPLAY;
        mOriginalWindowingMode = -1;
        synchronized (mPendingLock) {
            mPendingFocusedTaskId = -1;
        }
        if (displayId == Display.INVALID_DISPLAY || originalMode < 0) {
            return;
        }
        try {
            windowingAccess().set(displayId, originalMode);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // A disconnected secondary display no longer has state to restore.
            Log.i(TAG, "desktop display disappeared before focus cleanup: "
                    + displayId);
        }
    }

    private void drainFocusChanges() {
        while (true) {
            final int taskId;
            synchronized (mPendingLock) {
                taskId = mPendingFocusedTaskId;
                mPendingFocusedTaskId = -1;
                if (taskId < 0 || !mAcceptingEvents) {
                    mDrainScheduled = false;
                    return;
                }
            }
            repairFocus(taskId);
            synchronized (mPendingLock) {
                if (mPendingFocusedTaskId < 0 || !mAcceptingEvents) {
                    mDrainScheduled = false;
                    return;
                }
            }
        }
    }

    private void repairFocus(final int focusedTaskId) {
        final int displayId = mDisplayId;
        if (displayId == Display.INVALID_DISPLAY) {
            return;
        }
        try {
            if (HiddenTaskApi.findTask(
                    mTaskService, displayId, focusedTaskId) == null) {
                return;
            }
            final String inputState = InputStateDump.read();
            final int inputTaskId = TaskInputWindowParser.findFocusedTaskId(
                    inputState, displayId);
            synchronized (mPendingLock) {
                if (mPendingFocusedTaskId >= 0 || !mAcceptingEvents) {
                    return;
                }
            }
            if (inputTaskId < 0 || inputTaskId == focusedTaskId
                    || HiddenTaskApi.findTask(
                            mTaskService, displayId, inputTaskId) == null) {
                return;
            }
            if (mListener != null) {
                mListener.onInputFocusRefreshRequired();
            }
            Log.i(TAG, "reported stale desktop input focus display=" + displayId
                    + " task=" + focusedTaskId
                    + " staleInputTask=" + inputTaskId);
        } catch (IOException | ReflectiveOperationException
                | RuntimeException error) {
            Log.w(TAG, "could not repair desktop input focus", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private DisplayWindowingAccess windowingAccess()
            throws ReflectiveOperationException {
        if (mWindowingAccess == null) {
            mWindowingAccess = new DisplayWindowingAccess();
        }
        return mWindowingAccess;
    }

    private <T> T call(final Operation<T> operation) {
        final Future<T> result = mExecutor.submit(operation::run);
        try {
            return result.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "desktop focus operation interrupted", error);
        } catch (ExecutionException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(
                    "desktop focus operation failed", cause);
        }
    }

    private interface Operation<T> {
        T run();
    }

    private static final class DisplayWindowingAccess {
        final Object windowManager;
        final Method getWindowingMode;
        final Method setWindowingMode;

        DisplayWindowingAccess() throws ReflectiveOperationException {
            final IBinder binder = (IBinder) Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "window");
            if (binder == null) {
                throw new IllegalStateException(
                        "window service is unavailable");
            }
            final Class<?> interfaceType =
                    Class.forName("android.view.IWindowManager");
            windowManager = Class
                    .forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            if (windowManager == null) {
                throw new IllegalStateException(
                        "window manager interface is unavailable");
            }
            getWindowingMode = interfaceType.getMethod(
                    "getWindowingMode", Integer.TYPE);
            setWindowingMode = interfaceType.getMethod(
                    "setWindowingMode", Integer.TYPE, Integer.TYPE);
        }

        int get(final int displayId) throws ReflectiveOperationException {
            return ((Integer) getWindowingMode.invoke(
                    windowManager, Integer.valueOf(displayId))).intValue();
        }

        void set(final int displayId, final int mode)
                throws ReflectiveOperationException {
            setWindowingMode.invoke(
                    windowManager,
                    Integer.valueOf(displayId),
                    Integer.valueOf(mode));
        }
    }
}
