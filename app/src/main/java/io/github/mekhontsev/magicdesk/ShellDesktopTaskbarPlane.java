package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;

import java.util.LinkedHashSet;
import java.util.Set;

/** Owns the bounded organizer area containing the desktop taskbar task. */
final class ShellDesktopTaskbarPlane implements AutoCloseable {
    private static final String TAG = "MagicDeskTaskbarPlane";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final long TASK_REMOVAL_TIMEOUT_MILLIS = 1_000L;
    private static final long TASK_REMOVAL_POLL_MILLIS = 25L;

    private final Object mService;
    private final Rect mBounds = new Rect();

    private TaskDisplayAreaHandle mArea;
    private int mDisplayId = Display.INVALID_DISPLAY;
    private int mTaskId = -1;
    private boolean mVisible;

    ShellDesktopTaskbarPlane(final Object service) {
        mService = service;
    }

    synchronized void configure(final int displayId, final Rect bounds) {
        if (displayId == Display.INVALID_DISPLAY) {
            close();
            return;
        }
        requireBounds(bounds);
        if (mArea != null && mDisplayId == displayId && mTaskId >= 0) {
            updatePresentation(displayId, bounds, true);
            raise();
            return;
        }
        close();
        if (mArea != null) {
            throw new IllegalStateException(
                    "cannot replace an unremoved desktop taskbar plane");
        }
        mDisplayId = displayId;
        mBounds.set(bounds);
        mVisible = true;
        try {
            mArea = TaskDisplayAreaHandle.create(
                    displayId,
                    TaskDisplayAreaHandle.Parent.DEFAULT_TASK_CONTAINER,
                    "MagicDesk desktop taskbar");
            applyAreaBounds(bounds);
            mTaskId = TaskDisplayAreaLaunchCommand.launchFullscreenTaskBehind(
                    mService,
                    displayId,
                    DesktopTaskbarActivity.createIntent(displayId),
                    BuildConfig.APPLICATION_ID,
                    mArea.token());
            final Object task = HiddenTaskApi.requireTask(
                    mService, displayId, mTaskId);
            final ComponentName component = HiddenTaskApi.getTaskComponent(task);
            if (!DesktopTaskbarActivity.isTaskbarComponent(component)
                    || HiddenTaskApi.getTaskDisplayAreaFeatureId(task)
                            != mArea.featureId()) {
                throw new IllegalStateException(
                        "taskbar task did not enter its desktop plane");
            }
            configureTask(task);
            Log.i(TAG, "created display=" + displayId
                    + " feature=" + mArea.featureId()
                    + " task=" + mTaskId
                    + " bounds=" + mBounds);
        } catch (ReflectiveOperationException | RuntimeException error) {
            close();
            throw new IllegalStateException(
                    "cannot prepare desktop taskbar plane", error);
        }
    }

    synchronized void updatePresentation(
            final int displayId,
            final Rect bounds,
            final boolean visible) {
        requireBounds(bounds);
        if (mArea == null || mDisplayId != displayId) {
            throw new IllegalStateException(
                    "desktop taskbar plane is not configured for display "
                            + displayId);
        }
        if (mBounds.equals(bounds) && mVisible == visible) {
            return;
        }
        try {
            final FrameworkWindowingApi windowing =
                    FrameworkRuntime.current().windowing();
            final Class<?> transactionClass = windowing.transactionClass();
            final Object transaction = windowing.newTransaction();
            if (!mBounds.equals(bounds)) {
                final Object taskToken = HiddenTaskApi.requireTaskToken(
                        mService, mDisplayId, mTaskId);
                windowing.setBounds(
                        transaction, mArea.token(), new Rect(bounds));
                windowing.setBounds(transaction, taskToken, new Rect());
            }
            // Hiding only the child task leaves the organizer area's surface
            // composited on some WindowManager implementations. Hiding the
            // TaskDisplayArea is the framework operation that force-hides all
            // of its tasks as one presentation unit.
            windowing.setHidden(transaction, mArea.token(), !visible);
            if (visible) {
                windowing.setAlwaysOnTop(
                        transaction, mArea.token(), true);
                windowing.reorder(transaction, mArea.token(), true);
            }
            ShellWindowTransitionExecutor.applyAtomic(
                    mService, transactionClass, transaction);
            mBounds.set(bounds);
            mVisible = visible;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot update desktop taskbar presentation", error);
        }
    }

    synchronized void configureActivityInput(final IBinder activityToken) {
        if (mArea == null || mTaskId < 0 || activityToken == null) {
            throw new IllegalStateException(
                    "desktop taskbar plane is not ready for input");
        }
        FrameworkActivityInputApi.setRecordInputSinkEnabled(
                activityToken, false);
    }

    synchronized boolean isTaskbarTask(final Object task) {
        if (task == null || mTaskId < 0) {
            return false;
        }
        try {
            return HiddenTaskApi.getTaskId(task) == mTaskId
                    || DesktopTaskbarActivity.isTaskbarComponent(
                            HiddenTaskApi.getTaskComponent(task));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    synchronized void raise() {
        if (mArea == null || !mVisible) {
            return;
        }
        try {
            final FrameworkWindowingApi windowing =
                    FrameworkRuntime.current().windowing();
            final Class<?> transactionClass = windowing.transactionClass();
            final Object transaction = windowing.newTransaction();
            windowing.setAlwaysOnTop(transaction, mArea.token(), true);
            windowing.reorder(transaction, mArea.token(), true);
            windowing.setFocusable(transaction, mArea.token(), false);
            ShellWindowTransitionExecutor.applyAtomic(
                    mService, transactionClass, transaction);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot raise desktop taskbar plane", error);
        }
    }

    @Override
    public synchronized void close() {
        final TaskDisplayAreaHandle area = mArea;
        if (area == null) {
            clearState();
            return;
        }
        final int displayId = mDisplayId;
        final int taskId = mTaskId;
        final Set<Integer> ownedTaskIds = findOwnedTaskIds(
                area, displayId, taskId);
        if (!removeOwnedTasks(ownedTaskIds)) {
            Log.w(TAG, "could not remove taskbar tasks=" + ownedTaskIds
                    + "; retaining plane feature=" + area.featureId());
            return;
        }
        final boolean closed = area.closeIfEmpty(mService, displayId);
        if (closed) {
            clearState();
            return;
        }
        Log.w(TAG, "could not remove desktop taskbar plane feature="
                + area.featureId() + " task=" + taskId
                + "; retaining ownership for retry");
    }

    private void configureTask(final Object task)
            throws ReflectiveOperationException {
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        final Object taskToken = HiddenTaskApi.getTaskToken(task);
        windowing.setWindowingMode(
                transaction, taskToken, WINDOWING_MODE_FULLSCREEN);
        windowing.setBounds(transaction, taskToken, new Rect());
        windowing.setForceTranslucent(transaction, taskToken, true);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction, taskToken, true);
        windowing.setFocusable(transaction, mArea.token(), false);
        // Keep the taskbar in the same WindowManager ordering domain as
        // application and fullscreen planes, while retaining an isolated
        // parent that applications can never enter.
        windowing.setAlwaysOnTop(transaction, mArea.token(), true);
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
    }

    private void applyAreaBounds(final Rect bounds)
            throws ReflectiveOperationException {
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        windowing.setBounds(transaction, mArea.token(), new Rect(bounds));
        if (mTaskId >= 0) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    mService, mDisplayId, mTaskId);
            windowing.setBounds(transaction, taskToken, new Rect());
        }
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
    }

    private Set<Integer> findOwnedTaskIds(
            final TaskDisplayAreaHandle area,
            final int displayId,
            final int knownTaskId) {
        final Set<Integer> taskIds = new LinkedHashSet<>();
        try {
            for (final Object task : HiddenTaskApi.getAllTasks(mService)) {
                final int taskId = HiddenTaskApi.getTaskId(task);
                final boolean belongsToArea =
                        HiddenTaskApi.getTaskDisplayId(task) == displayId
                                && HiddenTaskApi
                                        .getTaskDisplayAreaFeatureId(task)
                                        == area.featureId();
                if ((taskId == knownTaskId || belongsToArea)
                        && DesktopTaskbarActivity.isTaskbarComponent(
                                HiddenTaskApi.getTaskComponent(task))) {
                    taskIds.add(Integer.valueOf(
                            taskId));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not enumerate taskbar plane children", error);
        }
        return taskIds;
    }

    private boolean removeOwnedTasks(final Set<Integer> taskIds) {
        if (taskIds.isEmpty()) {
            return true;
        }
        try {
            for (final Integer taskId : taskIds) {
                if (!HiddenTaskApi.removeTask(
                        mService, taskId.intValue())) {
                    return false;
                }
            }
            final Set<Integer> remaining =
                    BoundedStateAwaiter.awaitFramework(
                            BoundedStateAwaiter.Reason.TASK_REMOVAL,
                            TASK_REMOVAL_TIMEOUT_MILLIS,
                            TASK_REMOVAL_POLL_MILLIS,
                            () -> findExistingTaskIds(taskIds),
                            Set::isEmpty);
            return remaining.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not remove desktop taskbar tasks", error);
            return false;
        }
    }

    private Set<Integer> findExistingTaskIds(
            final Set<Integer> expectedTaskIds)
            throws ReflectiveOperationException {
        final Set<Integer> existingTaskIds = new LinkedHashSet<>();
        for (final Object task : HiddenTaskApi.getAllTasks(mService)) {
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getTaskId(task));
            if (expectedTaskIds.contains(taskId)) {
                existingTaskIds.add(taskId);
            }
        }
        return existingTaskIds;
    }

    private void clearState() {
        mArea = null;
        mDisplayId = Display.INVALID_DISPLAY;
        mTaskId = -1;
        mVisible = false;
        mBounds.setEmpty();
    }

    private static void requireBounds(final Rect bounds) {
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException(
                    "desktop taskbar plane requires non-empty bounds");
        }
    }
}
