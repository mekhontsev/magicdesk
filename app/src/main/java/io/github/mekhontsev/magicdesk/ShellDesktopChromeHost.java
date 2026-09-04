package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;

import java.util.LinkedHashSet;
import java.util.Set;

/** Owns the standard root task that supplies desktop application-window chrome. */
final class ShellDesktopChromeHost implements AutoCloseable {
    private static final String TAG = "MagicDeskChromeHost";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final long TASK_REMOVAL_TIMEOUT_MILLIS = 1_000L;
    private static final long TASK_REMOVAL_POLL_MILLIS = 25L;

    private final Object mService;

    private int mDisplayId = Display.INVALID_DISPLAY;
    private int mTaskId = -1;

    ShellDesktopChromeHost(final Object service) {
        mService = service;
    }

    synchronized void configure(final int displayId) {
        if (displayId == Display.INVALID_DISPLAY) {
            close();
            return;
        }
        if (mDisplayId == displayId && findOwnedTask() != null) {
            raise();
            return;
        }
        close();
        if (mTaskId >= 0) {
            throw new IllegalStateException(
                    "cannot replace an unremoved desktop chrome host");
        }
        mDisplayId = displayId;
        try {
            mTaskId = TaskDisplayAreaLaunchCommand.launchFullscreenTaskBehind(
                    mService,
                    displayId,
                    DesktopChromeActivity.createIntent(displayId),
                    BuildConfig.APPLICATION_ID,
                    null);
            final Object task = HiddenTaskApi.requireTask(
                    mService, displayId, mTaskId);
            if (!DesktopChromeActivity.isChromeComponent(
                    HiddenTaskApi.getTaskComponent(task))) {
                throw new IllegalStateException(
                        "desktop chrome host resolved to the wrong activity");
            }
            configureTask(task);
            Log.i(TAG, "created display=" + displayId + " task=" + mTaskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            close();
            throw new IllegalStateException(
                    "cannot prepare desktop chrome host", error);
        }
    }

    synchronized int prepare(final int displayId) {
        if (displayId != mDisplayId) {
            throw new IllegalStateException(
                    "stale chrome display " + displayId
                            + "; configured=" + mDisplayId);
        }
        Object task = findOwnedTask();
        if (task == null) {
            mTaskId = -1;
            configure(displayId);
            task = findOwnedTask();
        }
        if (task == null) {
            throw new IllegalStateException(
                    "desktop chrome host task is unavailable");
        }
        raise();
        return mTaskId;
    }

    synchronized boolean isChromeTask(final Object task) {
        if (task == null || mTaskId < 0) {
            return false;
        }
        try {
            return HiddenTaskApi.getTaskId(task) == mTaskId
                    || DesktopChromeActivity.isChromeComponent(
                            HiddenTaskApi.getTaskComponent(task));
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    synchronized void raise() {
        final Object task = findOwnedTask();
        if (task == null) {
            return;
        }
        try {
            final FrameworkWindowingApi windowing =
                    FrameworkRuntime.current().windowing();
            final Class<?> transactionClass = windowing.transactionClass();
            final Object transaction = windowing.newTransaction();
            final Object taskToken = HiddenTaskApi.getTaskToken(task);
            windowing.setAlwaysOnTop(transaction, taskToken, true);
            windowing.setFocusable(transaction, taskToken, false);
            windowing.reorder(transaction, taskToken, true);
            ShellWindowTransitionExecutor.applyAtomic(
                    mService, transactionClass, transaction);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot raise desktop chrome host", error);
        }
    }

    @Override
    public synchronized void close() {
        final Set<Integer> taskIds = findOwnedTaskIds();
        if (taskIds.isEmpty()) {
            clearState();
            return;
        }
        try {
            for (final Integer taskId : taskIds) {
                if (!HiddenTaskApi.removeTask(mService, taskId.intValue())) {
                    Log.w(TAG, "could not remove desktop chrome task="
                            + taskId);
                    return;
                }
            }
            final Set<Integer> remaining = BoundedStateAwaiter.awaitFramework(
                    BoundedStateAwaiter.Reason.TASK_REMOVAL,
                    TASK_REMOVAL_TIMEOUT_MILLIS,
                    TASK_REMOVAL_POLL_MILLIS,
                    () -> findExistingTaskIds(taskIds),
                    Set::isEmpty);
            if (remaining.isEmpty()) {
                clearState();
            } else {
                Log.w(TAG, "desktop chrome tasks remain=" + remaining);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not remove desktop chrome host", error);
        }
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
        windowing.setFocusable(transaction, taskToken, false);
        windowing.setAlwaysOnTop(transaction, taskToken, true);
        windowing.reorder(transaction, taskToken, true);
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
    }

    private Object findOwnedTask() {
        if (mDisplayId == Display.INVALID_DISPLAY || mTaskId < 0) {
            return null;
        }
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, mDisplayId, mTaskId);
            return task != null
                    && DesktopChromeActivity.isChromeComponent(
                            HiddenTaskApi.getTaskComponent(task))
                            ? task : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private Set<Integer> findOwnedTaskIds() {
        final Set<Integer> taskIds = new LinkedHashSet<>();
        if (mDisplayId == Display.INVALID_DISPLAY) {
            return taskIds;
        }
        try {
            for (final Object task : HiddenTaskApi.getTasks(
                    mService, mDisplayId)) {
                final int taskId = HiddenTaskApi.getTaskId(task);
                final ComponentName component =
                        HiddenTaskApi.getTaskComponent(task);
                if (taskId == mTaskId
                        || DesktopChromeActivity.isChromeComponent(component)) {
                    taskIds.add(Integer.valueOf(taskId));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not enumerate desktop chrome tasks", error);
        }
        return taskIds;
    }

    private Set<Integer> findExistingTaskIds(
            final Set<Integer> expectedTaskIds)
            throws ReflectiveOperationException {
        final Set<Integer> existingTaskIds = new LinkedHashSet<>();
        for (final Object task : HiddenTaskApi.getAllTasks(mService)) {
            final Integer taskId = Integer.valueOf(HiddenTaskApi.getTaskId(task));
            if (expectedTaskIds.contains(taskId)) {
                existingTaskIds.add(taskId);
            }
        }
        return existingTaskIds;
    }

    private void clearState() {
        mDisplayId = Display.INVALID_DISPLAY;
        mTaskId = -1;
    }
}
