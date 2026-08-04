package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DesktopTaskWatcher {
    interface Listener {
        boolean isActive(int generation);
        void onReady(int generation);
        void onChanged(int generation);
        void onImmersiveRequest(int generation, int taskId,
                boolean requesting, boolean initialSample);
        void onTaskGone(int generation, int taskId);
        void onNativeMaximizeChanged(
                int generation, int taskId, boolean enteredFullscreen);
        void onDisconnected(int generation);
    }

    private static final String TAG = "MagicDeskTasks";

    private final Handler mHandler;
    private final Listener mListener;
    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor();
    private final Map<Long, TaskRepository.ActionCallback> mFocusCallbacks =
            new HashMap<>();

    private long mNextFocusSequence;
    private ShellAccess.TaskObserverHandle mHandle;
    private TaskObserverCallback mCallback;
    private boolean mDestroyed;

    DesktopTaskWatcher(final Handler handler, final Listener listener) {
        mHandler = handler;
        mListener = listener;
    }

    void start(final int generation) {
        synchronized (this) {
            if (mDestroyed) {
                throw new IllegalStateException("task watcher is destroyed");
            }
            mExecutor.execute(() -> open(generation));
        }
    }

    synchronized void stop() {
        failPendingFocusCallbacks("task observer stopped");
        closeHandle();
    }

    synchronized void destroy() {
        if (mDestroyed) {
            return;
        }
        mDestroyed = true;
        stop();
        mExecutor.shutdownNow();
    }

    synchronized boolean configure(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds) {
        if (mHandle == null) {
            return false;
        }
        try {
            mHandle.configure(displayId, displayBounds, workAreaBounds);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "failed to configure task observer", error);
            recordFailure(
                    "TASK-OBSERVER-CONFIGURE-001",
                    "Could not configure desktop task monitoring",
                    "display=" + displayId,
                    error);
            return false;
        }
    }

    synchronized void sendFocusStack(
            final int displayId,
            final List<Integer> taskIds,
            final TaskRepository.ActionCallback callback) {
        if (mHandle == null) {
            completeFocusCallback(
                    callback, false, "task observer unavailable");
            return;
        }
        final long sequence = ++mNextFocusSequence;
        if (callback != null) {
            mFocusCallbacks.put(Long.valueOf(sequence), callback);
        }
        final int[] taskIdArray = new int[taskIds.size()];
        for (int index = 0; index < taskIds.size(); index++) {
            taskIdArray[index] = taskIds.get(index).intValue();
        }
        try {
            mHandle.focusStack(sequence, displayId, taskIdArray);
        } catch (IOException error) {
            mFocusCallbacks.remove(Long.valueOf(sequence));
            completeFocusCallback(
                    callback, false, "task observer focus failed");
            Log.w(TAG, "failed to focus task stack", error);
            recordFailure(
                    "TASK-OBSERVER-FOCUS-001",
                    "Could not focus the requested desktop tasks",
                    "display=" + displayId + " tasks=" + taskIds.size(),
                    error);
        }
    }

    private void open(final int generation) {
        final TaskObserverCallback callback =
                new TaskObserverCallback(this, generation);
        ShellAccess.TaskObserverHandle handle = null;
        try {
            handle = ShellAccess.openTaskObserver(
                    callback,
                    () -> observerDisconnected(generation, callback));
            if (handle.isClosed()) {
                throw new IOException("task observer disconnected during startup");
            }
            synchronized (this) {
                if (mDestroyed || !mListener.isActive(generation)
                        || handle.isClosed()) {
                    handle.close();
                    if (!mDestroyed && mListener.isActive(generation)) {
                        throw new IOException(
                                "task observer disconnected during startup");
                    }
                    return;
                }
                mHandle = handle;
                mCallback = callback;
            }
            postIfActive(generation, () -> mListener.onReady(generation));
        } catch (IOException error) {
            if (handle != null) {
                handle.close();
            }
            if (mListener.isActive(generation)) {
                Log.w(TAG, "task observer failed", error);
                recordFailure(
                        "TASK-OBSERVER-START-001",
                        "Desktop task monitoring could not start",
                        "shell=" + ShellAccess.statusLabel(),
                        error);
                postDisconnected(generation);
            }
        }
    }

    private void observerDisconnected(
            final int generation,
            final TaskObserverCallback callback) {
        synchronized (this) {
            if (mCallback != callback) {
                return;
            }
            mHandle = null;
            mCallback = null;
        }
        postDisconnected(generation);
    }

    private void postDisconnected(final int generation) {
        mHandler.post(() -> {
            if (!mListener.isActive(generation)) {
                return;
            }
            failPendingFocusCallbacks("task observer disconnected");
            mListener.onDisconnected(generation);
        });
    }

    private synchronized void closeHandle() {
        final ShellAccess.TaskObserverHandle handle = mHandle;
        mHandle = null;
        mCallback = null;
        if (handle != null) {
            handle.close();
        }
    }

    private void onTasksChanged(final int generation) {
        postIfActive(generation, () -> mListener.onChanged(generation));
    }

    private void onImmersiveRequest(
            final int generation,
            final int taskId,
            final boolean requesting,
            final boolean initialSample) {
        postIfActive(generation, () -> mListener.onImmersiveRequest(
                generation, taskId, requesting, initialSample));
    }

    private void onTaskGone(
            final int generation,
            final int taskId) {
        postIfActive(generation, () ->
                mListener.onTaskGone(generation, taskId));
    }

    private void onNativeMaximizeChanged(
            final int generation,
            final int taskId,
            final boolean enteredFullscreen) {
        postIfActive(generation, () ->
                mListener.onNativeMaximizeChanged(
                        generation, taskId, enteredFullscreen));
    }

    private void onFocusStackResult(
            final int generation,
            final long sequence,
            final boolean success,
            final int taskCount,
            final String error) {
        postIfActive(generation, () -> {
            final TaskRepository.ActionCallback callback;
            synchronized (DesktopTaskWatcher.this) {
                callback = mFocusCallbacks.remove(Long.valueOf(sequence));
            }
            final String message;
            if (success) {
                message = "focused " + taskCount + " tasks";
            } else if (error == null || error.isEmpty()) {
                message = "task stack focus failed";
            } else {
                message = error;
            }
            completeFocusCallback(callback, success, message);
        });
    }

    private void onObserverError(
            final int generation,
            final String error) {
        if (mListener.isActive(generation)) {
            Log.w(TAG, "task observer: " + error);
            recordFailure(
                    "TASK-OBSERVER-RUNTIME-001",
                    "Desktop task monitoring reported an error",
                    "error=" + error,
                    null);
        }
    }

    private static void recordFailure(
            final String code,
            final String message,
            final String detail,
            final Throwable error) {
        CompatibilityDiagnostics.record(code, message, detail, error);
    }

    private void postIfActive(
            final int generation,
            final Runnable action) {
        mHandler.post(() -> {
            if (mListener.isActive(generation)) {
                action.run();
            }
        });
    }

    private void failPendingFocusCallbacks(final String message) {
        final List<TaskRepository.ActionCallback> callbacks;
        synchronized (this) {
            if (mFocusCallbacks.isEmpty()) {
                return;
            }
            callbacks = new ArrayList<>(mFocusCallbacks.values());
            mFocusCallbacks.clear();
        }
        for (final TaskRepository.ActionCallback callback : callbacks) {
            completeFocusCallback(callback, false, message);
        }
    }

    private static void completeFocusCallback(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(
                    new TaskRepository.ActionResult(success, message));
        }
    }

    private static final class TaskObserverCallback
            extends ITaskObserverCallback.Stub {
        private final DesktopTaskWatcher mOwner;
        private final int mGeneration;

        TaskObserverCallback(
                final DesktopTaskWatcher owner,
                final int generation) {
            mOwner = owner;
            mGeneration = generation;
        }

        @Override
        public void onTasksChanged() throws RemoteException {
            mOwner.onTasksChanged(mGeneration);
        }

        @Override
        public void onImmersiveRequest(
                final int taskId,
                final boolean requesting,
                final boolean initialSample) throws RemoteException {
            mOwner.onImmersiveRequest(
                    mGeneration, taskId, requesting, initialSample);
        }

        @Override
        public void onTaskGone(final int taskId) throws RemoteException {
            mOwner.onTaskGone(mGeneration, taskId);
        }

        @Override
        public void onNativeMaximizeChanged(
                final int taskId,
                final boolean enteredFullscreen) throws RemoteException {
            mOwner.onNativeMaximizeChanged(
                    mGeneration, taskId, enteredFullscreen);
        }

        @Override
        public void onFocusStackResult(
                final long sequence,
                final boolean success,
                final int taskCount,
                final String error) throws RemoteException {
            mOwner.onFocusStackResult(
                    mGeneration, sequence, success, taskCount, error);
        }

        @Override
        public void onObserverError(final String error)
                throws RemoteException {
            mOwner.onObserverError(mGeneration, error);
        }
    }
}
