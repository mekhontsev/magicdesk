package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Keeps task and input focus synchronized on the active desktop display. */
final class ShellDesktopFocusController implements AutoCloseable {
    interface Listener {
        void onInputFocusRefreshRequired(int focusedTaskId);
    }

    private static final String TAG = "MagicDeskFocus";
    private static final long TASK_COMMIT_TIMEOUT_MILLIS = 700L;
    private static final long INPUT_FOCUS_COMMIT_TIMEOUT_MILLIS = 2_000L;

    static final class CommitBarrier {
        final long taskSampleGeneration;
        final long inputWindowGeneration;
        final boolean inputWindowEventsAvailable;

        CommitBarrier(
                final long taskSampleGeneration,
                final long inputWindowGeneration,
                final boolean inputWindowEventsAvailable) {
            this.taskSampleGeneration = taskSampleGeneration;
            this.inputWindowGeneration = inputWindowGeneration;
            this.inputWindowEventsAvailable = inputWindowEventsAvailable;
        }
    }

    private final Object mTaskService;
    private final Listener mListener;
    private final FrameworkInputWindowObservationSource
            mInputWindowObservations;
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
    private boolean mPendingConfirmationRequested;
    private int mFocusConfirmationTaskId = -1;
    private int mMissingWindowRepairTaskId = -1;
    private boolean mDrainScheduled;
    private boolean mAcceptingEvents = true;
    private boolean mAvailable;
    private long mTaskSampleGeneration;
    private long mInputFocusRefreshGeneration;
    private int mInputFocusRefreshTaskId = -1;

    ShellDesktopFocusController(
            final Object taskService,
            final boolean enabled,
            final FrameworkInputWindowObservationSource inputWindows,
            final Listener listener) {
        mTaskService = taskService;
        mListener = listener;
        mAvailable = enabled;
        mInputWindowObservations = enabled ? inputWindows : null;
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
        requestFocusReconciliation(taskId);
    }

    void requestFocusReconciliation(final int taskId) {
        enqueueFocusReconciliation(taskId, true);
    }

    CommitBarrier captureCommitBarrier() {
        synchronized (mPendingLock) {
            return new CommitBarrier(
                    mTaskSampleGeneration,
                    mInputWindowObservations == null
                            ? 0L : mInputWindowObservations.checkpoint(),
                    mInputWindowObservations != null
                            && mInputWindowObservations.isAvailable());
        }
    }

    private long taskSampleGeneration() {
        synchronized (mPendingLock) {
            return mTaskSampleGeneration;
        }
    }

    /** Completes a workspace command only after its input target is usable. */
    boolean convergeAfterCommit(
            final int taskId,
            final CommitBarrier barrier,
            final Runnable sampleRequester) {
        if (taskId < 0 || barrier == null || sampleRequester == null) {
            return false;
        }
        return call(() -> convergeAfterCommitOnWorker(
                taskId, barrier, sampleRequester));
    }

    /** Completes a structural task commit without claiming input focus. */
    boolean convergeTaskAfterCommit(
            final int taskId,
            final CommitBarrier barrier) {
        if (taskId < 0 || barrier == null) {
            return false;
        }
        return call(() -> convergeTaskAfterCommitOnWorker(taskId, barrier));
    }

    void onTasksSampled(final List<FrameworkTaskSnapshot> tasks) {
        final int confirmationTaskId;
        synchronized (mPendingLock) {
            mTaskSampleGeneration++;
            mPendingLock.notifyAll();
            confirmationTaskId = mFocusConfirmationTaskId;
        }
        if (confirmationTaskId < 0 || tasks == null) {
            return;
        }
        if (!isFocusConfirmationReady(confirmationTaskId, tasks)) {
            return;
        }
        synchronized (mPendingLock) {
            if (mFocusConfirmationTaskId != confirmationTaskId) {
                return;
            }
            mFocusConfirmationTaskId = -1;
        }
        // Organizer children do not always expose isFocused=true, and sibling
        // organizer planes have no reliable child order in RootTaskInfo. The
        // requested task already identifies the owner; a visible typed sample
        // is only the commit barrier before the strict InputDispatcher check.
        enqueueFocusReconciliation(confirmationTaskId, false);
    }

    void onInputFocusRefreshCompleted(final int taskId) {
        if (taskId < 0) {
            return;
        }
        synchronized (mPendingLock) {
            mInputFocusRefreshTaskId = taskId;
            mInputFocusRefreshGeneration++;
            mPendingLock.notifyAll();
        }
    }

    static boolean isFocusConfirmationReady(
            final int confirmationTaskId,
            final List<FrameworkTaskSnapshot> tasks) {
        if (confirmationTaskId < 0 || tasks == null) {
            return false;
        }
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task.taskId == confirmationTaskId
                    && task.visible
                    && (task.activityType == FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD
                            || isDesktopHostSnapshot(task))
                    && !DesktopInfrastructureTasks.isTask(task)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDesktopHostSnapshot(
            final FrameworkTaskSnapshot task) {
        return task.activityType == FrameworkTaskSnapshot.ACTIVITY_TYPE_HOME
                && (BuildConfig.APPLICATION_ID.equals(task.topPackage)
                        || BuildConfig.APPLICATION_ID.equals(
                                task.packageName))
                && (isDesktopHostComponentName(task.topActivityName)
                        || isDesktopHostComponentName(task.componentName));
    }

    private static boolean isDesktopHostComponentName(
            final String componentName) {
        return DesktopHostComponents.isHostComponentName(componentName);
    }

    private void enqueueFocusReconciliation(
            final int taskId,
            final boolean requestConfirmation) {
        if (taskId < 0) {
            return;
        }
        synchronized (mPendingLock) {
            if (!mAcceptingEvents) {
                return;
            }
            mPendingFocusedTaskId = taskId;
            mPendingConfirmationRequested = requestConfirmation;
            if (requestConfirmation) {
                mFocusConfirmationTaskId = -1;
            }
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
            mPendingConfirmationRequested = false;
            mFocusConfirmationTaskId = -1;
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
        final int desktopDisplayId = mAvailable
                && displayId >= Display.DEFAULT_DISPLAY
                ? displayId : Display.INVALID_DISPLAY;
        if (mDisplayId == desktopDisplayId) {
            return;
        }
        clearConfigurationOnWorker();
        if (desktopDisplayId == Display.INVALID_DISPLAY) {
            return;
        }
        mDisplayId = desktopDisplayId;
    }

    private void clearConfigurationOnWorker() {
        mDisplayId = Display.INVALID_DISPLAY;
        synchronized (mPendingLock) {
            mPendingFocusedTaskId = -1;
            mPendingConfirmationRequested = false;
            mFocusConfirmationTaskId = -1;
            mMissingWindowRepairTaskId = -1;
        }
    }

    private void drainFocusChanges() {
        while (true) {
            final int taskId;
            final boolean requestConfirmation;
            synchronized (mPendingLock) {
                taskId = mPendingFocusedTaskId;
                requestConfirmation = mPendingConfirmationRequested;
                mPendingFocusedTaskId = -1;
                mPendingConfirmationRequested = false;
                if (taskId < 0 || !mAcceptingEvents) {
                    mDrainScheduled = false;
                    return;
                }
            }
            repairFocus(taskId, requestConfirmation);
            synchronized (mPendingLock) {
                if (mPendingFocusedTaskId < 0 || !mAcceptingEvents) {
                    mDrainScheduled = false;
                    return;
                }
            }
        }
    }

    private void repairFocus(
            final int focusedTaskId,
            final boolean requestConfirmation) {
        final int displayId = mDisplayId;
        if (displayId == Display.INVALID_DISPLAY) {
            return;
        }
        boolean taskObserved = false;
        try {
            final Object focusedTask = HiddenTaskApi.findTask(
                    mTaskService, displayId, focusedTaskId);
            if (focusedTask == null) {
                return;
            }
            taskObserved = true;
            final String inputState =
                    FrameworkInputSnapshotSource.readLocal();
            if (TaskInputWindowParser.isTaskFocused(
                    inputState, displayId, focusedTaskId)) {
                synchronized (mPendingLock) {
                    mMissingWindowRepairTaskId = -1;
                }
                return;
            }
            final int inputTaskId = TaskInputWindowParser.findFocusedTaskId(
                    inputState, displayId);
            synchronized (mPendingLock) {
                if (mPendingFocusedTaskId >= 0 || !mAcceptingEvents) {
                    return;
                }
            }
            if (!requiresInputFocusRefresh(
                    focusedTaskId, inputTaskId,
                    inputTaskId >= 0 && HiddenTaskApi.findTask(
                            mTaskService, displayId, inputTaskId) != null)) {
                return;
            }
            boolean hierarchyRepair = false;
            synchronized (mPendingLock) {
                if (inputTaskId < 0
                        && mMissingWindowRepairTaskId != focusedTaskId) {
                    mMissingWindowRepairTaskId = focusedTaskId;
                    hierarchyRepair = true;
                } else if (inputTaskId >= 0) {
                    mMissingWindowRepairTaskId = -1;
                }
            }
            if (hierarchyRepair) {
                final int windowingMode =
                        HiddenTaskApi.getTaskWindowingMode(focusedTask);
                if (requiresParentReorderForMissingWindow(windowingMode)) {
                    // A freeform task is its own root. Reorder that root through
                    // the normal activation path so WindowManager can rebuild
                    // the missing input target exposed after another root dies.
                    TaskWindowingCommand.focusTasks(
                            mTaskService,
                            displayId,
                            new int[] {focusedTaskId});
                } else {
                    // Fullscreen tasks keep a stable organizer plane. Reassert
                    // only the child order; moving the parent here would bypass
                    // the fullscreen topology owner.
                    TaskWindowingCommand.focusTasksWithinCurrentParent(
                            mTaskService,
                            displayId,
                            new int[] {focusedTaskId});
                }
            }
            if (mListener != null) {
                mListener.onInputFocusRefreshRequired(focusedTaskId);
            }
            Log.i(TAG, "reported stale desktop input focus display=" + displayId
                    + " task=" + focusedTaskId
                    + " staleInputTask=" + inputTaskId
                    + " hierarchyRepair=" + hierarchyRepair
                    + " parentRepair=" + (hierarchyRepair
                            && requiresParentReorderForMissingWindow(
                                    HiddenTaskApi.getTaskWindowingMode(
                                            focusedTask))));
        } catch (IOException | ReflectiveOperationException
                | RuntimeException error) {
            Log.w(TAG, "could not repair desktop input focus", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (requestConfirmation && taskObserved) {
                armFocusConfirmation(focusedTaskId);
            }
        }
    }

    private boolean convergeAfterCommitOnWorker(
            final int taskId,
            final CommitBarrier barrier,
            final Runnable sampleRequester) {
        final int displayId = mDisplayId;
        if (displayId == Display.INVALID_DISPLAY) {
            return true;
        }
        try {
            final Object task = HiddenTaskApi.findTask(
                    mTaskService, displayId, taskId);
            if (task == null) {
                return false;
            }
            awaitTaskSample(barrier.taskSampleGeneration);
            final ComponentName topActivity =
                    HiddenTaskApi.getTaskTopActivity(task);
            final boolean desktopHostTarget = isDesktopHostTarget(
                    HiddenTaskApi.getTaskActivityType(task),
                    topActivity == null
                            ? null : topActivity.getPackageName(),
                    topActivity == null
                            ? null : topActivity.getClassName());
            // The host cannot acquire input while it is behind an application.
            // Once its task commit is observed, stale focus requires the
            // existing relayout repair rather than the normal convergence wait.
            final boolean initiallyFocused = desktopHostTarget
                    ? isInputFocused(displayId, taskId)
                    : awaitCommittedInputFocus(
                            displayId,
                            taskId,
                            barrier.inputWindowGeneration,
                            barrier.inputWindowEventsAvailable);
            if (initiallyFocused) {
                synchronized (mPendingLock) {
                    mMissingWindowRepairTaskId = -1;
                }
                return true;
            }
            final long repairInputWindowGeneration =
                    inputWindowGeneration();
            final long refreshGeneration = inputFocusRefreshGeneration();
            final boolean refreshRequested = repairMissingInputTarget(
                    displayId, taskId, task);
            if (refreshRequested) {
                awaitInputFocusRefresh(taskId, refreshGeneration);
                if (!isInputFocused(displayId, taskId)
                        && HiddenTaskApi.getTaskWindowingMode(task)
                                == FrameworkTaskSnapshot
                                        .WINDOWING_MODE_FULLSCREEN) {
                    // Release the stale workspace window first. Reordering
                    // the plane child before the host relayout lets that
                    // later relayout clear the newly selected input target.
                    TaskWindowingCommand.focusTasksWithinCurrentParent(
                            mTaskService, displayId, new int[]{taskId});
                }
            }
            final long repairedSampleGeneration = taskSampleGeneration();
            sampleRequester.run();
            awaitTaskSample(repairedSampleGeneration);
            final boolean converged = awaitCommittedInputFocus(
                    displayId,
                    taskId,
                    repairInputWindowGeneration,
                    mInputWindowObservations != null
                            && mInputWindowObservations.isAvailable());
            if (converged) {
                synchronized (mPendingLock) {
                    mMissingWindowRepairTaskId = -1;
                }
            } else {
                final String inputState =
                        FrameworkInputSnapshotSource.readLocal();
                Log.w(TAG, "desktop focus convergence expired display="
                        + displayId + " task=" + taskId + "; "
                        + TaskInputWindowParser.describeFocus(
                                inputState, displayId));
            }
            return converged;
        } catch (IOException | ReflectiveOperationException
                | RuntimeException error) {
            Log.w(TAG, "could not confirm committed desktop focus", error);
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean convergeTaskAfterCommitOnWorker(
            final int taskId,
            final CommitBarrier barrier) {
        final int displayId = mDisplayId;
        if (displayId == Display.INVALID_DISPLAY) {
            return true;
        }
        try {
            if (!awaitTaskSample(barrier.taskSampleGeneration)) {
                Log.w(TAG, "desktop task commit sample expired display="
                        + displayId + " task=" + taskId);
                return false;
            }
            final Object task = HiddenTaskApi.findTask(
                    mTaskService, displayId, taskId);
            return task != null && HiddenTaskApi.isTaskVisible(task);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not confirm committed desktop task", error);
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long inputWindowGeneration() {
        return mInputWindowObservations == null
                ? 0L : mInputWindowObservations.checkpoint();
    }

    private boolean awaitCommittedInputFocus(
            final int displayId,
            final int taskId,
            final long initialGeneration,
            final boolean eventsAvailable)
            throws IOException, InterruptedException {
        return InputFocusCommitAwaiter.await(
                eventsAvailable ? mInputWindowObservations : null,
                initialGeneration,
                INPUT_FOCUS_COMMIT_TIMEOUT_MILLIS,
                () -> isInputFocused(displayId, taskId));
    }

    private boolean awaitTaskSample(final long previousGeneration)
            throws InterruptedException {
        final long deadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                        TASK_COMMIT_TIMEOUT_MILLIS);
        synchronized (mPendingLock) {
            while (mAcceptingEvents
                    && mTaskSampleGeneration <= previousGeneration) {
                final long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                EventDrivenWaits.await(
                        mPendingLock,
                        EventDrivenWaits.Reason.FRAMEWORK_OBSERVER_RESAMPLE,
                        Math.max(1L,
                                java.util.concurrent.TimeUnit.NANOSECONDS
                                        .toMillis(remainingNanos)));
            }
            return mTaskSampleGeneration > previousGeneration;
        }
    }

    private static boolean isInputFocused(
            final int displayId,
            final int taskId) throws IOException, InterruptedException {
        return TaskInputWindowParser.isTaskFocused(
                FrameworkInputSnapshotSource.readLocal(),
                displayId,
                taskId);
    }

    private long inputFocusRefreshGeneration() {
        synchronized (mPendingLock) {
            return mInputFocusRefreshGeneration;
        }
    }

    private boolean awaitInputFocusRefresh(
            final int taskId,
            final long previousGeneration) throws InterruptedException {
        final long deadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                        TASK_COMMIT_TIMEOUT_MILLIS);
        synchronized (mPendingLock) {
            while (mAcceptingEvents
                    && (mInputFocusRefreshGeneration <= previousGeneration
                            || mInputFocusRefreshTaskId != taskId)) {
                final long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                EventDrivenWaits.await(
                        mPendingLock,
                        EventDrivenWaits.Reason.INPUT_FOCUS_RELAYOUT,
                        Math.max(1L,
                                java.util.concurrent.TimeUnit.NANOSECONDS
                                        .toMillis(remainingNanos)));
            }
            return mInputFocusRefreshGeneration > previousGeneration
                    && mInputFocusRefreshTaskId == taskId;
        }
    }

    private boolean repairMissingInputTarget(
            final int displayId,
            final int taskId,
            final Object task)
            throws IOException, ReflectiveOperationException,
            InterruptedException {
        final String inputState = FrameworkInputSnapshotSource.readLocal();
        final int inputTaskId = TaskInputWindowParser.findFocusedTaskId(
                inputState, displayId);
        final boolean inputTaskExists = inputTaskId >= 0
                && HiddenTaskApi.findTask(
                        mTaskService, displayId, inputTaskId) != null;
        if (!requiresInputFocusRefresh(
                taskId, inputTaskId, inputTaskExists)) {
            return false;
        }
        synchronized (mPendingLock) {
            mMissingWindowRepairTaskId = taskId;
        }
        if (mListener != null) {
            mListener.onInputFocusRefreshRequired(taskId);
        }
        Log.i(TAG, "requested desktop input relayout display="
                + displayId + " task=" + taskId
                + " staleInputTask=" + inputTaskId);
        return mListener != null;
    }

    private void armFocusConfirmation(final int taskId) {
        synchronized (mPendingLock) {
            if (mAcceptingEvents && mPendingFocusedTaskId < 0) {
                mFocusConfirmationTaskId = taskId;
            }
        }
    }

    static boolean requiresParentReorderForMissingWindow(
            final int windowingMode) {
        return windowingMode == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM;
    }

    static boolean isDesktopHostTarget(
            final int activityType,
            final String packageName,
            final String className) {
        return activityType == FrameworkTaskSnapshot.ACTIVITY_TYPE_HOME
                && BuildConfig.APPLICATION_ID.equals(packageName)
                && DesktopHostComponents.isHostClassName(className);
    }

    static boolean requiresInputFocusRefresh(
            final int focusedTaskId,
            final int inputTaskId,
            final boolean inputTaskExists) {
        if (focusedTaskId < 0 || inputTaskId == focusedTaskId) {
            return false;
        }
        // A missing focused window is the other stale-focus state observed on
        // secondary displays. Relayout the desktop host just as when focus is
        // still attached to a different live task.
        return inputTaskId < 0 || inputTaskExists;
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
