package io.github.mekhontsev.magicdesk;

import android.util.Log;
import android.view.Display;

import java.io.IOException;
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

    private int mDisplayId = Display.INVALID_DISPLAY;
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
            // Submit while holding the same lock used by close(), so the
            // executor cannot be shut down between acceptance and enqueueing.
            mExecutor.execute(this::drainFocusChanges);
        }
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
        mDisplayId = secondaryDisplayId;
    }

    private void clearConfigurationOnWorker() {
        mDisplayId = Display.INVALID_DISPLAY;
        synchronized (mPendingLock) {
            mPendingFocusedTaskId = -1;
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

}
