package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;

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
                boolean foreground);
        void onTaskRequestedOrientationChanged(
                int generation, int taskId, int requestedOrientation);
        void onTaskGone(int generation, int taskId);
        void onWindowingModeChanged(
                int generation,
                int taskId,
                int previousMode,
                int currentMode,
                int previousCaptionSourceId,
                boolean backgroundAppFullscreenReleased);
        void onFreeformBoundsChanged(
                int generation,
                int taskId,
                String stateKey,
                int displayId,
                Rect bounds);
        void onInputFocusRefreshRequired(
                int generation, int focusedTaskId);
        void onTaskFocusChanged(
                int generation, int taskId, int displayId, boolean focused);
        void onDesktopTaskAreaForegroundChanged(
                int generation, boolean foreground);
        void onDesktopTaskOwnershipChanged(
                int generation, int displayId, int[] taskIds);
        void onSystemDialogVisibilityChanged(
                int generation, int displayId, boolean visible);
        void onDisconnected(int generation);
    }

    private static final String TAG = "MagicDeskTasks";

    private final Handler mHandler;
    private final Listener mListener;
    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor();
    private final LatestOperationSerializer mConfigurationOperations =
            new LatestOperationSerializer();
    private final Map<Long, TaskRepository.ActionCallback> mFocusCallbacks =
            new HashMap<>();

    private long mNextFocusSequence;
    private long mLifecycleGeneration;
    private ShellTaskObserverHandle mHandle;
    private TaskObserverCallback mCallback;
    private boolean mPhoneTouchpadRequested;
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
            mConfigurationOperations.invalidate();
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
            final int taskAreaPolicy,
            final int desktopHostTaskId) {
        final ShellTaskObserverHandle handle;
        final LatestOperationSerializer.Ticket ticket;
        synchronized (this) {
            handle = mHandle;
            if (handle == null) {
                return false;
            }
            ticket = mConfigurationOperations.supersede();
        }
        try {
            // clearConfiguration() is intentionally asynchronous. Serialize it
            // with replacement configurations so cleanup from the previous
            // display can never dismantle the newly registered session area.
            return mConfigurationOperations.executeIfCurrent(ticket, () ->
                    handle.configure(
                            displayId,
                            displayBounds,
                            workAreaBounds,
                            taskAreaPolicy,
                            desktopHostTaskId));
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

    void clearConfiguration(final int expectedDisplayId) {
        if (expectedDisplayId < 0) {
            return;
        }
        final ShellTaskObserverHandle handle;
        final LatestOperationSerializer.Ticket ticket;
        synchronized (this) {
            handle = mHandle;
            if (handle == null) {
                return;
            }
            ticket = mConfigurationOperations.supersede();
        }
        try {
            mExecutor.execute(() -> {
                try {
                    mConfigurationOperations.executeIfCurrent(ticket, () ->
                            handle.clearConfiguration(expectedDisplayId));
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
                intent,
                bounds);
    }

    int launchFullscreenTaskInManagedSession(
            final int displayId,
            final Intent intent) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null || intent == null) {
            throw new IOException("desktop task area is unavailable");
        }
        return handle.launchFullscreenTaskInManagedSession(
                displayId, intent);
    }

    int launchFullscreenTask(
            final int displayId,
            final Intent intent) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null || intent == null) {
            throw new IOException("desktop task observer is unavailable");
        }
        return handle.launchFullscreenTask(
                displayId, intent);
    }

    int launchAppShortcut(
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user,
            final int windowingMode,
            final Rect bounds,
            final int existingTaskId) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            throw new IOException("desktop task observer is unavailable");
        }
        return handle.launchAppShortcut(
                displayId,
                packageName,
                shortcutId,
                user,
                windowingMode,
                bounds,
                existingTaskId);
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
                intent);
    }

    void placeWindowedTaskInManagedSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            throw new IOException("desktop task area is unavailable");
        }
        handle.placeWindowedTaskInManagedSession(
                taskId, sourceDisplayId, targetDisplayId, bounds);
    }

    void placeFullscreenTaskInManagedSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws IOException {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            throw new IOException("desktop task area is unavailable");
        }
        handle.placeFullscreenTaskInManagedSession(
                taskId, sourceDisplayId, targetDisplayId);
    }

    void sendFocusStack(
            final int displayId,
            final List<Integer> taskIds,
            final TaskRepository.ActionCallback callback) {
        if (taskIds == null || taskIds.isEmpty()) {
            completeFocusCallback(callback, false, "no tasks");
            return;
        }
        final int[] taskIdArray = new int[taskIds.size()];
        for (int index = 0; index < taskIds.size(); index++) {
            taskIdArray[index] = taskIds.get(index).intValue();
        }
        sendWorkspaceCommand(
                DesktopWorkspaceCommand.create(
                        DesktopWorkspaceCommand.ACTIVATE,
                        displayId,
                        taskIdArray[taskIdArray.length - 1],
                        taskIdArray),
                callback);
    }

    void sendWorkspaceCommand(
            final DesktopWorkspaceCommand command,
            final TaskRepository.ActionCallback callback) {
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
            mExecutor.execute(() -> {
                try {
                    handle.executeWorkspaceCommand(sequence, command);
                } catch (IOException error) {
                    failWorkspaceCommand(sequence, command, error);
                }
            });
        } catch (RejectedExecutionException error) {
            failWorkspaceCommand(sequence, command, error);
        }
    }

    void notifyInputFocusRefreshComplete(final int taskId) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null || taskId < 0) {
            return;
        }
        // Workspace submission occupies the observer command executor until
        // shell-side convergence completes. Send the frame acknowledgement on
        // the independent task queue so the convergence barrier can wake.
        TaskCommandQueue.execute(() -> {
            try {
                handle.notifyInputFocusRefreshComplete(taskId);
            } catch (IOException error) {
                Log.w(TAG, "failed to acknowledge input focus relayout", error);
            }
        });
    }

    private void failWorkspaceCommand(
            final long sequence,
            final DesktopWorkspaceCommand command,
            final Exception error) {
        final TaskRepository.ActionCallback failedCallback;
        synchronized (this) {
            failedCallback = mFocusCallbacks.remove(
                    Long.valueOf(sequence));
        }
        completeFocusCallback(
                failedCallback, false, "workspace command failed");
        Log.w(TAG, "failed to execute workspace command", error);
        recordFailure(
                "TASK-OBSERVER-FOCUS-001",
                "Could not execute the requested workspace command",
                command == null ? "missing command" : command.toString(),
                error);
    }

    boolean restoreFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect bounds,
            final TaskRepository.ActionCallback callback) {
        final Rect requestedBounds = bounds == null ? null : new Rect(bounds);
        return submitTaskMutation(
                "restore fullscreen task",
                taskId,
                handle -> handle.restoreFullscreenTask(
                        displayId, taskId, requestedBounds),
                callback);
    }

    boolean concealFullscreenTaskPlanes(
            final int displayId,
            final TaskRepository.ActionCallback callback) {
        return submitTaskMutation(
                "conceal fullscreen planes",
                -1,
                handle -> handle.concealFullscreenTaskPlanes(displayId),
                callback);
    }

    boolean beginAppFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect restoreBounds,
            final TaskRepository.ActionCallback callback) {
        final Rect requestedRestoreBounds = restoreBounds == null
                ? null : new Rect(restoreBounds);
        return submitTaskMutation(
                "begin app fullscreen task",
                taskId,
                handle -> handle.beginAppFullscreenTask(
                        displayId, taskId, requestedRestoreBounds),
                callback);
    }

    boolean beginFullscreenTask(
            final int displayId,
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        return submitTaskMutation(
                "begin fullscreen task",
                taskId,
                handle -> handle.beginFullscreenTask(displayId, taskId),
                callback);
    }

    boolean protectExplicitFullscreenTask(
            final int displayId,
            final int taskId) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return false;
        }
        try {
            return handle.protectExplicitFullscreenTask(displayId, taskId);
        } catch (IOException error) {
            Log.w(TAG, "failed to protect explicit fullscreen task="
                    + taskId, error);
            return false;
        }
    }

    boolean closeDesktopTask(
            final int displayId,
            final int taskId,
            final int focusTaskId,
            final TaskRepository.ActionCallback callback) {
        return submitTaskMutation(
                "close desktop task",
                taskId,
                handle -> handle.closeDesktopTask(
                        displayId, taskId, focusTaskId),
                callback);
    }

    private boolean submitTaskMutation(
            final String operation,
            final int taskId,
            final TaskMutation mutation,
            final TaskRepository.ActionCallback callback) {
        final ShellTaskObserverHandle handle;
        final long lifecycleGeneration;
        synchronized (this) {
            handle = mHandle;
            lifecycleGeneration = mLifecycleGeneration;
            if (mDestroyed || handle == null) {
                return false;
            }
        }
        try {
            mExecutor.execute(() -> {
                synchronized (DesktopTaskWatcher.this) {
                    if (mDestroyed
                            || lifecycleGeneration != mLifecycleGeneration
                            || handle != mHandle) {
                        postMutationResult(
                                callback, false, "task observer changed");
                        return;
                    }
                }
                boolean success = false;
                String message = operation + " rejected";
                try {
                    success = mutation.apply(handle);
                    if (success) {
                        message = operation + " completed";
                    }
                } catch (IOException error) {
                    message = ShellAccess.usefulMessage(error);
                    Log.w(TAG, "failed to " + operation
                            + " task=" + taskId, error);
                }
                postMutationResult(callback, success, message);
            });
            return true;
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "task observer mutation executor stopped", error);
            return false;
        }
    }

    private void postMutationResult(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        mHandler.post(() -> completeFocusCallback(
                callback, success, message));
    }

    private interface TaskMutation {
        boolean apply(ShellTaskObserverHandle handle) throws IOException;
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

    TaskWindowSnapshot inspectTaskWindow(
            final int displayId,
            final int taskId) {
        final ShellTaskObserverHandle handle = currentHandle();
        if (handle == null) {
            return null;
        }
        try {
            return handle.inspectTaskWindow(displayId, taskId);
        } catch (IOException error) {
            Log.w(TAG, "failed to inspect task window=" + taskId, error);
            return null;
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

    synchronized boolean setPhoneTouchpadRequested(
            final boolean requested) {
        mPhoneTouchpadRequested = requested;
        if (mHandle == null) {
            return false;
        }
        try {
            mHandle.setPhoneTouchpadRequested(requested);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "failed to update phone touchpad request", error);
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
        final ActivityLaunchCallback activityLauncher =
                new ActivityLaunchCallback(this, generation);
        ShellTaskObserverHandle handle = null;
        try {
            handle = ShellAccess.openTaskObserver(
                    callback,
                    activityLauncher,
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
                    handle.setPhoneTouchpadRequested(
                            mPhoneTouchpadRequested);
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
            mConfigurationOperations.invalidate();
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
            final boolean foreground) {
        postIfActive(generation, () -> mListener.onImmersiveRequest(
                generation,
                taskId,
                requesting,
                initialSample,
                foreground));
    }

    private void onTaskRequestedOrientationChanged(
            final int generation,
            final int taskId,
            final int requestedOrientation) {
        postIfActive(generation, () ->
                mListener.onTaskRequestedOrientationChanged(
                        generation, taskId, requestedOrientation));
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
            final int previousCaptionSourceId,
            final boolean backgroundAppFullscreenReleased) {
        postIfActive(generation, () ->
                mListener.onWindowingModeChanged(
                        generation,
                        taskId,
                        previousMode,
                        currentMode,
                        previousCaptionSourceId,
                        backgroundAppFullscreenReleased));
    }

    private void onFreeformBoundsChanged(
            final int generation,
            final int taskId,
            final String stateKey,
            final int displayId,
            final Rect bounds) {
        final Rect snapshot = bounds == null ? null : new Rect(bounds);
        postIfActive(generation, () ->
                mListener.onFreeformBoundsChanged(
                        generation,
                        taskId,
                        stateKey,
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

    private void onInputFocusRefreshRequired(
            final int generation,
            final int focusedTaskId) {
        postIfActive(generation, () ->
                mListener.onInputFocusRefreshRequired(
                        generation, focusedTaskId));
    }

    private void onTaskFocusChanged(
            final int generation,
            final int taskId,
            final int displayId,
            final boolean focused) {
        postIfActive(generation, () ->
                mListener.onTaskFocusChanged(
                        generation, taskId, displayId, focused));
    }

    private void onDesktopTaskAreaForegroundChanged(
            final int generation,
            final boolean foreground) {
        postIfActive(generation, () ->
                mListener.onDesktopTaskAreaForegroundChanged(
                        generation, foreground));
    }

    private void onDesktopTaskOwnershipChanged(
            final int generation,
            final int displayId,
            final int[] taskIds) {
        final int[] snapshot = taskIds == null
                ? new int[0] : taskIds.clone();
        postIfActive(generation, () ->
                mListener.onDesktopTaskOwnershipChanged(
                        generation, displayId, snapshot));
    }

    private void onSystemDialogVisibilityChanged(
            final int generation,
            final int displayId,
            final boolean visible) {
        postIfActive(generation, () ->
                mListener.onSystemDialogVisibilityChanged(
                        generation, displayId, visible));
    }

    private void onPhoneTaskNormalized(
            final int generation,
            final int taskId) {
        if (mListener.isActive(generation)) {
            PhoneTaskNormalizationDiagnostics.noteNormalization(taskId);
        }
    }

    private void onTaskActivityModeCorrected(
            final int generation,
            final int taskId,
            final String activityName,
            final String restoredMode) {
        if (mListener.isActive(generation)) {
            TaskActivityModeDiagnostics.noteCorrection(
                    taskId, activityName, restoredMode);
            DesktopWindowTransitionProvenance.noteActivityHandoff(
                    taskId, restoredMode, activityName);
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
            DesktopProcessHealthRegistry.record(
                    type,
                    processName,
                    pid,
                    taskId,
                    displayId,
                    reason);
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
            try {
                DesktopAutomationEventJournal.record(
                        "process",
                        type == DesktopProcessFailure.ANR ? "anr" : "crash",
                        false,
                        processName,
                        new org.json.JSONObject()
                                .put("process", processName)
                                .put("pid", pid)
                                .put("taskId", taskId)
                                .put("displayId", displayId)
                                .put("windowingMode", windowingMode)
                                .put("topActivity", topActivity)
                                .put("reason", reason));
            } catch (org.json.JSONException ignored) {
                DesktopAutomationEventJournal.record(
                        "process",
                        type == DesktopProcessFailure.ANR ? "anr" : "crash",
                        false,
                        processName);
            }
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
                final boolean foreground) throws RemoteException {
            mOwner.onImmersiveRequest(
                    mGeneration,
                    taskId,
                    requesting,
                    initialSample,
                    foreground);
        }

        @Override
        public void onTaskRequestedOrientationChanged(
                final int taskId,
                final int requestedOrientation) throws RemoteException {
            mOwner.onTaskRequestedOrientationChanged(
                    mGeneration, taskId, requestedOrientation);
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
                final int previousCaptionSourceId,
                final boolean backgroundAppFullscreenReleased)
                throws RemoteException {
            mOwner.onWindowingModeChanged(
                    mGeneration,
                    taskId,
                    previousMode,
                    currentMode,
                    previousCaptionSourceId,
                    backgroundAppFullscreenReleased);
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
        public void onDesktopWorkspaceCommandResult(
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
                final String stateKey,
                final int displayId,
                final int left,
                final int top,
                final int right,
                final int bottom) throws RemoteException {
            mOwner.onFreeformBoundsChanged(
                    mGeneration,
                    taskId,
                    stateKey,
                    displayId,
                    new Rect(left, top, right, bottom));
        }

        @Override
        public void onInputFocusRefreshRequired(final int focusedTaskId)
                throws RemoteException {
            mOwner.onInputFocusRefreshRequired(
                    mGeneration, focusedTaskId);
        }

        @Override
        public void onTaskFocusChanged(
                final int taskId,
                final int displayId,
                final boolean focused) throws RemoteException {
            mOwner.onTaskFocusChanged(
                    mGeneration, taskId, displayId, focused);
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
        public void onTaskActivityModeCorrected(
                final int taskId,
                final String activityName,
                final String restoredMode) throws RemoteException {
            mOwner.onTaskActivityModeCorrected(
                    mGeneration, taskId, activityName, restoredMode);
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

        @Override
        public void onDesktopTaskOwnershipChanged(
                final int displayId,
                final int[] taskIds) throws RemoteException {
            mOwner.onDesktopTaskOwnershipChanged(
                    mGeneration, displayId, taskIds);
        }

        @Override
        public void onSystemDialogVisibilityChanged(
                final int displayId,
                final boolean visible) throws RemoteException {
            mOwner.onSystemDialogVisibilityChanged(
                    mGeneration, displayId, visible);
        }

    }

    private static final class ActivityLaunchCallback
            extends IActivityLaunchCallback.Stub {
        private final DesktopTaskWatcher mOwner;
        private final int mGeneration;

        ActivityLaunchCallback(
                final DesktopTaskWatcher owner,
                final int generation) {
            mOwner = owner;
            mGeneration = generation;
        }

        @Override
        public void sendPendingIntent(
                final PendingIntent pendingIntent,
                final Bundle launchOptions) throws RemoteException {
            if (pendingIntent == null
                    || !mOwner.mListener.isActive(mGeneration)) {
                throw new RemoteException(
                        "desktop activity launcher is not active");
            }
            if (launchOptions == null) {
                throw new RemoteException("missing activity launch options");
            }
            try {
                pendingIntent.send(
                        MagicDeskApplication.applicationContext(),
                        0,
                        null,
                        null,
                        null,
                        null,
                        launchOptions);
            } catch (PendingIntent.CanceledException | RuntimeException error) {
                final RemoteException remote = new RemoteException(
                        "published shortcut launch failed: "
                                + ShellAccess.usefulMessage(error));
                remote.initCause(error);
                throw remote;
            }
        }

        @Override
        public void presentPhoneOverview() throws RemoteException {
            final DesktopHomeRoleLease.State lease =
                    DesktopHomeRoleLease.snapshot();
            if (!mOwner.mListener.isActive(mGeneration)
                    || lease == null
                    || lease.phase != DesktopHomeRoleLease.Phase.ACTIVE) {
                throw new RemoteException(
                        "desktop HOME activity launcher is not active");
            }
            try {
                final ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
                MagicDeskApplication.applicationContext().startActivity(
                        PhoneHomeActivity.createOverviewIntent(
                                MagicDeskApplication.applicationContext()),
                        options.toBundle());
            } catch (RuntimeException error) {
                final RemoteException remote = new RemoteException(
                        "phone Overview launch failed: "
                                + ShellAccess.usefulMessage(error));
                remote.initCause(error);
                throw remote;
            }
        }
    }
}
