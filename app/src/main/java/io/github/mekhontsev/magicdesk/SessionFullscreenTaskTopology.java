package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps phone-desktop fullscreen tasks inside the shared session parent. */
final class SessionFullscreenTaskTopology
        implements ShellFullscreenTaskTopology {
    private static final String TAG = "MagicDeskSessionFullscreen";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final ShellDesktopTaskOwnership mOwnership;
    private final Map<Integer, Rect> mAppRestoreBounds = new HashMap<>();

    private int mDisplayId = -1;

    SessionFullscreenTaskTopology(
            final ShellDesktopTaskOwnership ownership) {
        if (ownership == null) {
            throw new IllegalArgumentException(
                    "desktop task ownership is required");
        }
        mOwnership = ownership;
    }

    @Override
    public synchronized ShellFullscreenTaskArea.FocusResult focusStack(
            final Object service,
            final int displayId,
            final int[] taskIds) {
        if (displayId != mDisplayId || taskIds == null
                || taskIds.length == 0) {
            return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
        }
        try {
            final int targetTaskId = taskIds[taskIds.length - 1];
            if (mOwnership.isDesktopHostTask(targetTaskId)) {
                return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
            }
            final Object targetTask = HiddenTaskApi.requireTask(
                    service, displayId, targetTaskId);
            if (!mOwnership.isDesktopTask(targetTask)) {
                return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
            }
            final int[] sessionTaskIds = desktopSessionTasks(
                    service, displayId, taskIds);
            if (sessionTaskIds.length == 0) {
                return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
            }
            TaskWindowingCommand.focusTasksWithinCurrentParent(
                    service, displayId, sessionTaskIds);
            return ShellFullscreenTaskArea.FocusResult.SESSION_FOREGROUND;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "phone session focus failed", error);
            return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
        }
    }

    @Override
    public synchronized boolean concealForShowDesktop(final int displayId) {
        return displayId == mDisplayId;
    }

    @Override
    public synchronized boolean beginAppFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect restoreBounds) {
        if (displayId != mDisplayId || restoreBounds == null
                || restoreBounds.isEmpty()) {
            return false;
        }
        try {
            final FrameworkWindowingApi windowing =
                    FrameworkRuntime.current().windowing();
            final Class<?> transactionClass = windowing.transactionClass();
            final Object transaction = windowing.newTransaction();
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            windowing.setWindowingMode(
                    transaction, taskToken, WINDOWING_MODE_FULLSCREEN);
            windowing.setBounds(transaction, taskToken, new Rect());
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transaction, taskToken, true);
            TaskWindowingCommand.addFocusTasksWithinCurrentParent(
                    service,
                    displayId,
                    new int[]{taskId},
                    transactionClass,
                    transaction);
            ShellWindowTransitionExecutor.startForShellAdoption(
                    displayId,
                    ShellWindowTransitionExecutor.SystemTransition.CHANGE,
                    transactionClass,
                    transaction,
                    "enter-phone-application-fullscreen");
            mAppRestoreBounds.put(
                    Integer.valueOf(taskId), new Rect(restoreBounds));
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "phone app fullscreen failed task=" + taskId, error);
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
            return false;
        }
    }

    @Override
    public synchronized boolean beginFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean refreshCaption) {
        if (displayId != mDisplayId || taskId < 0) {
            return false;
        }
        try {
            TaskFullscreenTransitionCommand.applyFullscreen(
                    displayId, taskId, refreshCaption);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "phone fullscreen failed task=" + taskId, error);
            return false;
        }
    }

    @Override
    public synchronized boolean restoreTask(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) {
        final Integer taskKey = Integer.valueOf(taskId);
        final Rect appRestoreBounds = mAppRestoreBounds.get(taskKey);
        final Rect restoreBounds = appRestoreBounds == null
                ? bounds : appRestoreBounds;
        if (displayId != mDisplayId || restoreBounds == null
                || restoreBounds.isEmpty()) {
            return false;
        }
        boolean hidden = false;
        try {
            ShellPreparedTaskTransition.prepareFullscreen(
                    service, displayId, taskId);
            hidden = true;
            TaskDisplayAreaLaunchCommand.waitForTaskVisibility(
                    service, displayId, taskId, false);
            ShellPreparedTaskTransition.showPreparedFreeform(
                    service, displayId, taskId, restoreBounds);
            TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                    service, displayId, taskId, restoreBounds);
            hidden = false;
            mAppRestoreBounds.remove(taskKey);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "phone app fullscreen restore failed task="
                    + taskId, error);
            if (hidden) {
                try {
                    ShellPreparedTaskTransition.restorePreparedTask(
                            service,
                            displayId,
                            taskId,
                            WINDOWING_MODE_FREEFORM,
                            restoreBounds);
                    mAppRestoreBounds.remove(taskKey);
                    return true;
                } catch (ReflectiveOperationException
                        | RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            return false;
        }
    }

    @Override
    public synchronized ShellFullscreenTaskArea.CloseResult closeTask(
            final Object service,
            final int displayId,
            final int taskId,
            final int focusTaskId) {
        if (displayId != mDisplayId || taskId < 0
                || focusTaskId < 0 || focusTaskId == taskId) {
            return ShellFullscreenTaskArea.CloseResult.NOT_HANDLED;
        }
        try {
            final Object task = HiddenTaskApi.findTask(
                    service, displayId, taskId);
            if (!mOwnership.isDesktopTask(task)
                    || HiddenTaskApi.getTaskWindowingMode(task)
                            != WINDOWING_MODE_FULLSCREEN) {
                return ShellFullscreenTaskArea.CloseResult.NOT_HANDLED;
            }
            int successorTaskId = focusTaskId;
            if (mOwnership.isDesktopHostTask(successorTaskId)) {
                successorTaskId = findFullscreenSurvivor(
                        service, displayId, taskId);
            }
            if (successorTaskId < 0) {
                // The generic session close can hand focus to its HOME host.
                return ShellFullscreenTaskArea.CloseResult.NOT_HANDLED;
            }
            final Object successor = HiddenTaskApi.findTask(
                    service, displayId, successorTaskId);
            if (!mOwnership.isDesktopTask(successor)) {
                return ShellFullscreenTaskArea.CloseResult.FAILED;
            }
            TaskWindowingCommand.closeFullscreenAreaTask(
                    service, displayId, taskId, successorTaskId);
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
            Log.i(TAG, "closed phone fullscreen task=" + taskId
                    + " successor=" + successorTaskId
                    + " display=" + displayId);
            return ShellFullscreenTaskArea.CloseResult.SUCCEEDED;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "phone fullscreen close failed task=" + taskId, error);
            return ShellFullscreenTaskArea.CloseResult.FAILED;
        }
    }

    @Override
    public boolean onWindowingModeChanged(
            final int displayId,
            final int taskId,
            final int windowingMode,
            final boolean focused) {
        return false;
    }

    @Override
    public synchronized void onTaskRemoved(final int taskId) {
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
    }

    private int findFullscreenSurvivor(
            final Object service,
            final int displayId,
            final int closingTaskId) throws ReflectiveOperationException {
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int taskId = HiddenTaskApi.getTaskId(task);
            if (taskId != closingTaskId
                    && !mOwnership.isDesktopHostTask(taskId)
                    && !TaskAreaBackstopActivity.isBackstopComponent(
                            HiddenTaskApi.getTaskComponent(task))
                    && mOwnership.isDesktopTask(task)
                    && HiddenTaskApi.getTaskWindowingMode(task)
                            == WINDOWING_MODE_FULLSCREEN) {
                return taskId;
            }
        }
        return -1;
    }

    @Override
    public void onTaskMovedToFront(
            final int displayId,
            final int taskId) {
    }

    @Override
    public void onTaskStackChanged() {
    }

    @Override
    public synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
        }
    }

    @Override
    public synchronized void configure(
            final int displayId,
            final DesktopTaskAreaPolicy taskAreaPolicy) {
        if (displayId < 0) {
            close();
            return;
        }
        if (taskAreaPolicy != DesktopTaskAreaPolicy.SESSION) {
            throw new IllegalArgumentException(
                    "phone fullscreen topology requires session ownership");
        }
        if (mDisplayId != displayId) {
            close();
        }
        mDisplayId = displayId;
    }

    private int[] desktopSessionTasks(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        final List<Integer> output = new ArrayList<>();
        for (final int taskId : taskIds) {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            if (mOwnership.isDesktopHostTask(taskId)
                    || mOwnership.isDesktopTask(task)) {
                output.add(Integer.valueOf(taskId));
            }
        }
        final int[] result = new int[output.size()];
        for (int index = 0; index < output.size(); index++) {
            result[index] = output.get(index).intValue();
        }
        return result;
    }

    @Override
    public synchronized void close() {
        mAppRestoreBounds.clear();
        mDisplayId = -1;
    }
}
