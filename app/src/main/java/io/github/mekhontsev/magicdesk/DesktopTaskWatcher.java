package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class DesktopTaskWatcher {
    interface Listener {
        boolean isActive(int generation);
        void onReady(int generation);
        void onChanged(int generation);
        void onImmersiveRequest(int generation, int taskId,
                boolean requesting, boolean initialSample,
                boolean restoredByObserver);
        void onTaskGone(int generation, int taskId);
        void onWindowingModeChanged(
                int generation,
                int taskId,
                int previousMode,
                int currentMode,
                int previousCaptionSourceId);
        void onFreeformBoundsChanged(
                int generation,
                int taskId,
                String packageName,
                int displayId,
                Rect bounds);
        void onInputFocusRefreshRequired(int generation);
        void onDesktopTaskAreaForegroundChanged(
                int generation, boolean foreground);
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
    private long mLifecycleGeneration;
    private ShellTaskObserverHandle mHandle;
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
            final long lifecycleGeneration = ++mLifecycleGeneration;
            mExecutor.execute(() -> open(generation, lifecycleGeneration));
        }
    }

    void stop() {
        stop(null);
    }

    void stop(final Runnable completion) {
        final List<TaskRepository.ActionCallback> callbacks;
        final ShellTaskObserverHandle handle;
        synchronized (this) {
            mLifecycleGeneration++;
            callbacks = drainPendingFocusCallbacksLocked();
            handle = detachHandleLocked();
        }
        completeFocusCallbacks(
                callbacks, false, "task observer stopped");
        try {
            mExecutor.execute(() -> {
                try {
                    if (handle != null) {
                        handle.close();
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "failed to close task observer", error);
                } finally {
                    completeStop(completion);
                }
            });
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "task observer cleanup executor stopped", error);
            completeStop(completion);
        }
    }

    void destroy() {
        synchronized (this) {
            if (mDestroyed) {
                return;
            }
            mDestroyed = true;
        }
        stop();
        // Let the serialized observer close queued by stop() finish.
        mExecutor.shutdown();
    }

    boolean configure(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds,
            final boolean managedTaskArea,
            final int managedTaskAreaHostTaskId) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            handle.configure(
                    displayId,
                    displayBounds,
                    workAreaBounds,
                    managedTaskArea,
                    managedTaskAreaHostTaskId);
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

    void clearConfiguration() {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return;
        }
        try {
            mExecutor.execute(() -> {
                try {
                    handle.configure(-1, new Rect(), new Rect(), false, -1);
                } catch (IOException error) {
                    Log.w(TAG,
                            "failed to clear task observer configuration",
                            error);
                }
            });
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "task observer cleanup executor stopped", error);
        }
    }

    private static void completeStop(final Runnable completion) {
        if (completion != null) {
            completion.run();
        }
    }

    int launchWindowedTask(
            final int displayId,
            final Intent intent,
            final Rect bounds) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null || intent == null) {
            throw new IOException("desktop task area is unavailable");
        }
        return handle.launchWindowedTask(
                displayId,
                intent.toUri(Intent.URI_INTENT_SCHEME),
                bounds);
    }

    int launchFullscreenTaskInDesktopArea(
            final int displayId,
            final Intent intent) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null || intent == null) {
            throw new IOException("desktop task area is unavailable");
        }
        return handle.launchFullscreenTaskInDesktopArea(
                displayId,
                intent.toUri(Intent.URI_INTENT_SCHEME));
    }

    void launchTaskAction(
            final int displayId,
            final int taskId,
            final Intent intent) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null || intent == null) {
            throw new IOException("desktop task area is unavailable");
        }
        handle.launchTaskAction(
                displayId,
                taskId,
                intent.toUri(Intent.URI_INTENT_SCHEME));
    }

    void placeTaskInDesktopArea(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            throw new IOException("desktop task area is unavailable");
        }
        handle.placeTaskInDesktopArea(
                taskId, sourceDisplayId, targetDisplayId, bounds);
    }

    void placeFullscreenTaskInDesktopArea(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            throw new IOException("desktop task area is unavailable");
        }
        handle.placeFullscreenTaskInDesktopArea(
                taskId, sourceDisplayId, targetDisplayId);
    }

    void sendFocusStack(
            final int displayId,
            final List<Integer> taskIds,
            final TaskRepository.ActionCallback callback) {
        final int[] taskIdArray = new int[taskIds.size()];
        for (int index = 0; index < taskIds.size(); index++) {
            taskIdArray[index] = taskIds.get(index).intValue();
        }
        final ShellTaskObserverHandle handle;
        final long sequence;
        synchronized (this) {
            handle = mHandle;
            if (handle == null) {
                sequence = -1L;
            } else {
                sequence = ++mNextFocusSequence;
                if (callback != null) {
                    mFocusCallbacks.put(Long.valueOf(sequence), callback);
                }
            }
        }
        if (handle == null) {
            completeFocusCallback(
                    callback, false, "task observer unavailable");
            return;
        }
        try {
            handle.focusStack(sequence, displayId, taskIdArray);
        } catch (IOException error) {
            final TaskRepository.ActionCallback failedCallback;
            synchronized (this) {
                failedCallback = mFocusCallbacks.remove(
                        Long.valueOf(sequence));
            }
            completeFocusCallback(
                    failedCallback, false, "task observer focus failed");
            Log.w(TAG, "failed to focus task stack", error);
            recordFailure(
                    "TASK-OBSERVER-FOCUS-001",
                    "Could not focus the requested desktop tasks",
                    "display=" + displayId + " tasks=" + taskIds.size(),
                    error);
        }
    }

    boolean restoreFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect bounds) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            return handle.restoreFullscreenTask(displayId, taskId, bounds);
        } catch (IOException error) {
            Log.w(TAG, "failed to restore fullscreen task=" + taskId, error);
            return false;
        }
    }

    boolean beginAppFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect restoreBounds) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            return handle.beginAppFullscreenTask(
                    displayId, taskId, restoreBounds);
        } catch (IOException error) {
            Log.w(TAG, "failed to begin app fullscreen task=" + taskId, error);
            return false;
        }
    }

    boolean beginFullscreenTask(
            final int displayId,
            final int taskId) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            return handle.beginFullscreenTask(displayId, taskId);
        } catch (IOException error) {
            Log.w(TAG, "failed to begin fullscreen task=" + taskId, error);
            return false;
        }
    }

    boolean closeFullscreenTask(
            final int displayId,
            final int taskId) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            return handle.closeFullscreenTask(displayId, taskId);
        } catch (IOException error) {
            Log.w(TAG, "failed to close fullscreen task=" + taskId, error);
            return false;
        }
    }

    boolean closeDesktopTask(
            final int displayId,
            final int taskId,
            final int focusTaskId) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            return handle.closeDesktopTask(
                    displayId, taskId, focusTaskId);
        } catch (IOException error) {
            Log.w(TAG, "failed to close desktop task=" + taskId, error);
            return false;
        }
    }

    void removeDesktopPackageTasks(
            final int displayId,
            final String packageName,
            final int focusTaskId,
            final TaskRepository.ActionCallback callback) {
        try {
            mExecutor.execute(() -> {
                final ShellTaskObserverHandle handle = currentHandle();
                boolean success = false;
                String message = "task observer unavailable";
                if (handle != null) {
                    try {
                        success = handle.removeDesktopPackageTasks(
                                displayId, packageName, focusTaskId);
                        message = success
                                ? "desktop package tasks removed"
                                : "desktop package task removal rejected";
                    } catch (IOException error) {
                        message = ShellAccess.usefulMessage(error);
                        Log.w(TAG,
                                "failed to remove desktop package tasks",
                                error);
                    }
                }
                final boolean result = success;
                final String resultMessage = message;
                mHandler.post(() -> completeFocusCallback(
                        callback, result, resultMessage));
            });
        } catch (RejectedExecutionException error) {
            mHandler.post(() -> completeFocusCallback(
                    callback, false, "task observer stopped"));
        }
    }

    boolean startSelfTestTaskStackGuard(
            final int displayId,
            final int hostTaskId,
            final String stage) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            handle.startSelfTestTaskStackGuard(
                    displayId, hostTaskId, stage);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "failed to start self-test task-stack guard", error);
            return false;
        }
    }

    void setSelfTestTaskStackGuardStage(final String stage) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return;
        }
        try {
            handle.setSelfTestTaskStackGuardStage(stage);
        } catch (IOException error) {
            Log.w(TAG, "failed to update self-test task-stack stage", error);
        }
    }

    SelfTestTaskStackReport stopSelfTestTaskStackGuard() {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return SelfTestTaskStackReport.unavailable(
                    "task observer unavailable");
        }
        try {
            return handle.stopSelfTestTaskStackGuard();
        } catch (IOException error) {
            Log.w(TAG, "failed to stop self-test task-stack guard", error);
            return SelfTestTaskStackReport.unavailable(
                    ShellAccess.usefulMessage(error));
        }
    }

    boolean setPhoneTouchpadPreservation(
            final boolean enabled) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            handle.setPhoneTouchpadPreservation(enabled);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "failed to "
                    + (enabled ? "start" : "finish")
                    + " phone touchpad preservation", error);
            return false;
        }
    }

    boolean setExternalTaskMigrationProtection(
            final boolean enabled) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            handle.setExternalTaskMigrationProtection(enabled);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "failed to "
                    + (enabled ? "enable" : "disable")
                    + " external task migration protection", error);
            return false;
        }
    }

    boolean refreshTaskCaption(
            final int displayId,
            final int taskId,
            final int sourceId) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            handle.refreshTaskCaption(displayId, taskId, sourceId);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "failed to refresh native fullscreen caption", error);
            recordFailure(
                    "TASK-CAPTION-REFRESH-001",
                    "Could not clear a stale fullscreen caption inset",
                    "display=" + displayId + " task=" + taskId,
                    error);
            return false;
        }
    }

    private void open(
            final int generation,
            final long lifecycleGeneration) {
        final TaskObserverCallback callback =
                new TaskObserverCallback(this, generation);
        ShellTaskObserverHandle handle = null;
        try {
            handle = ShellAccess.openTaskObserver(
                    callback,
                    () -> observerDisconnected(generation, callback));
            if (handle.isClosed()) {
                throw new IOException("task observer disconnected during startup");
            }
            final boolean active = mListener.isActive(generation);
            final boolean installed;
            final boolean destroyed;
            synchronized (this) {
                destroyed = mDestroyed;
                installed = !destroyed
                        && lifecycleGeneration == mLifecycleGeneration
                        && active
                        && !handle.isClosed();
                if (installed) {
                    mHandle = handle;
                    mCallback = callback;
                }
            }
            if (!installed) {
                handle.close();
                if (!destroyed && active) {
                    throw new IOException(
                            "task observer disconnected during startup");
                }
                return;
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

    private synchronized ShellTaskObserverHandle currentHandle() {
        return mHandle;
    }

    private ShellTaskObserverHandle detachHandleLocked() {
        final ShellTaskObserverHandle handle = mHandle;
        mHandle = null;
        mCallback = null;
        return handle;
    }

    private void onTasksChanged(final int generation) {
        postIfActive(generation, () -> mListener.onChanged(generation));
    }

    private void onImmersiveRequest(
            final int generation,
            final int taskId,
            final boolean requesting,
            final boolean initialSample,
            final boolean restoredByObserver) {
        postIfActive(generation, () -> mListener.onImmersiveRequest(
                generation,
                taskId,
                requesting,
                initialSample,
                restoredByObserver));
    }

    private void onTaskGone(
            final int generation,
            final int taskId) {
        postIfActive(generation, () ->
                mListener.onTaskGone(generation, taskId));
    }

    private void onWindowingModeChanged(
            final int generation,
            final int taskId,
            final int previousMode,
            final int currentMode,
            final int previousCaptionSourceId) {
        postIfActive(generation, () ->
                mListener.onWindowingModeChanged(
                        generation,
                        taskId,
                        previousMode,
                        currentMode,
                        previousCaptionSourceId));
    }

    private void onFreeformBoundsChanged(
            final int generation,
            final int taskId,
            final String packageName,
            final int displayId,
            final Rect bounds) {
        final Rect snapshot = bounds == null ? null : new Rect(bounds);
        postIfActive(generation, () ->
                mListener.onFreeformBoundsChanged(
                        generation,
                        taskId,
                        packageName,
                        displayId,
                        snapshot));
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

    private void onInputFocusRefreshRequired(final int generation) {
        postIfActive(generation, () ->
                mListener.onInputFocusRefreshRequired(generation));
    }

    private void onDesktopTaskAreaForegroundChanged(
            final int generation,
            final boolean foreground) {
        postIfActive(generation, () ->
                mListener.onDesktopTaskAreaForegroundChanged(
                        generation, foreground));
    }

    private void onPhoneTaskNormalized(
            final int generation,
            final int taskId) {
        if (mListener.isActive(generation)) {
            PhoneTaskGuardDiagnostics.noteNormalization(taskId);
        }
    }

    private void onWindowedTaskStartupCorrected(
            final int generation,
            final int taskId,
            final String activityName) {
        if (mListener.isActive(generation)) {
            WindowedTaskStartupDiagnostics.noteCorrection(
                    taskId, activityName);
        }
    }

    private void onDesktopProcessFailure(
            final int generation,
            final int type,
            final String processName,
            final int pid,
            final int taskId,
            final int displayId,
            final int windowingMode,
            final String topActivity,
            final String reason) {
        postIfActive(generation, () -> {
            final String code = DesktopProcessFailure.code(type);
            final String message = DesktopProcessFailure.message(type);
            if (code.isEmpty() || message.isEmpty()) {
                Log.w(TAG, "ignored unknown process failure type=" + type);
                return;
            }
            CompatibilityDiagnostics.record(
                    code,
                    message,
                    DesktopProcessFailure.technicalDetail(
                            processName,
                            pid,
                            taskId,
                            displayId,
                            windowingMode,
                            topActivity,
                            reason));
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
            callbacks = drainPendingFocusCallbacksLocked();
        }
        completeFocusCallbacks(callbacks, false, message);
    }

    private List<TaskRepository.ActionCallback>
            drainPendingFocusCallbacksLocked() {
        if (mFocusCallbacks.isEmpty()) {
            return Collections.emptyList();
        }
        final List<TaskRepository.ActionCallback> callbacks =
                new ArrayList<>(mFocusCallbacks.values());
        mFocusCallbacks.clear();
        return callbacks;
    }

    private static void completeFocusCallbacks(
            final List<TaskRepository.ActionCallback> callbacks,
            final boolean success,
            final String message) {
        for (final TaskRepository.ActionCallback callback : callbacks) {
            completeFocusCallback(callback, success, message);
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
                final boolean initialSample,
                final boolean restoredByObserver) throws RemoteException {
            mOwner.onImmersiveRequest(
                    mGeneration,
                    taskId,
                    requesting,
                    initialSample,
                    restoredByObserver);
        }

        @Override
        public void onTaskGone(final int taskId) throws RemoteException {
            mOwner.onTaskGone(mGeneration, taskId);
        }

        @Override
        public void onWindowingModeChanged(
                final int taskId,
                final int previousMode,
                final int currentMode,
                final int previousCaptionSourceId) throws RemoteException {
            mOwner.onWindowingModeChanged(
                    mGeneration,
                    taskId,
                    previousMode,
                    currentMode,
                    previousCaptionSourceId);
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

        @Override
        public void onFreeformBoundsChanged(
                final int taskId,
                final String packageName,
                final int displayId,
                final int left,
                final int top,
                final int right,
                final int bottom) throws RemoteException {
            mOwner.onFreeformBoundsChanged(
                    mGeneration,
                    taskId,
                    packageName,
                    displayId,
                    new Rect(left, top, right, bottom));
        }

        @Override
        public void onInputFocusRefreshRequired()
                throws RemoteException {
            mOwner.onInputFocusRefreshRequired(mGeneration);
        }

        @Override
        public void onPhoneTaskNormalized(final int taskId)
                throws RemoteException {
            mOwner.onPhoneTaskNormalized(mGeneration, taskId);
        }

        @Override
        public void onDesktopTaskAreaForegroundChanged(
                final boolean foreground) throws RemoteException {
            mOwner.onDesktopTaskAreaForegroundChanged(
                    mGeneration, foreground);
        }

        @Override
        public void onWindowedTaskStartupCorrected(
                final int taskId,
                final String activityName) throws RemoteException {
            mOwner.onWindowedTaskStartupCorrected(
                    mGeneration, taskId, activityName);
        }

        @Override
        public void onDesktopProcessFailure(
                final int type,
                final String processName,
                final int pid,
                final int taskId,
                final int displayId,
                final int windowingMode,
                final String topActivity,
                final String reason) throws RemoteException {
            mOwner.onDesktopProcessFailure(
                    mGeneration,
                    type,
                    processName,
                    pid,
                    taskId,
                    displayId,
                    windowingMode,
                    topActivity,
                    reason);
        }
    }
}
