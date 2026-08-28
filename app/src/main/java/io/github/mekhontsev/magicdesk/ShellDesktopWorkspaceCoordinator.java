package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.util.Arrays;

/** Serializes semantic workspace commands over the existing task topology. */
final class ShellDesktopWorkspaceCoordinator {
    interface ForegroundReporter {
        void reportForTask(int taskId);
        void reportSessionForeground(boolean foreground);
    }

    static final class Result {
        final boolean success;
        final int taskCount;
        final String error;

        private Result(
                final boolean success,
                final int taskCount,
                final String error) {
            this.success = success;
            this.taskCount = taskCount;
            this.error = error == null ? "" : error;
        }

        static Result success(final int taskCount) {
            return new Result(true, taskCount, "");
        }

        static Result failure(final int taskCount, final String error) {
            return new Result(false, taskCount, error);
        }
    }

    private static final String TAG = "MagicDeskWorkspace";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    private final Object mService;
    private final ShellFullscreenTaskArea mFullscreenTaskArea;
    private final ShellDesktopFocusController mFocusController;
    private final ForegroundReporter mForegroundReporter;
    private final Runnable mTaskSampleRequester;

    ShellDesktopWorkspaceCoordinator(
            final Object service,
            final ShellFullscreenTaskArea fullscreenTaskArea,
            final ShellDesktopFocusController focusController,
            final ForegroundReporter foregroundReporter,
            final Runnable taskSampleRequester) {
        if (service == null || fullscreenTaskArea == null
                || focusController == null || foregroundReporter == null
                || taskSampleRequester == null) {
            throw new IllegalArgumentException(
                    "workspace coordinator dependencies are required");
        }
        mService = service;
        mFullscreenTaskArea = fullscreenTaskArea;
        mFocusController = focusController;
        mForegroundReporter = foregroundReporter;
        mTaskSampleRequester = taskSampleRequester;
    }

    synchronized Result execute(final DesktopWorkspaceCommand command) {
        int appliedTaskCount = 0;
        try {
            if (command == null) {
                throw new IllegalArgumentException(
                        "missing desktop workspace command");
            }
            command.validate();
            final int[] liveTaskIds = new int[
                    command.backToFrontTaskIds.length];
            for (int index = 0;
                    index < command.backToFrontTaskIds.length;
                    index++) {
                final int taskId = command.backToFrontTaskIds[index];
                if (HiddenTaskApi.findTask(
                        mService, command.displayId, taskId) == null) {
                    if (taskId == command.targetTaskId) {
                        throw new IllegalStateException(
                                "task " + taskId + " not found on display "
                                        + command.displayId);
                    }
                    Log.w(TAG, "workspace command skipped stale task="
                            + taskId + " operation="
                            + command.operationName());
                    continue;
                }
                liveTaskIds[appliedTaskCount++] = taskId;
            }
            if (appliedTaskCount == 0) {
                throw new IllegalStateException(
                        "no live tasks in workspace command");
            }
            final int[] physicalOrder = Arrays.copyOf(
                    liveTaskIds, appliedTaskCount);
            if (physicalOrder[physicalOrder.length - 1]
                    != command.targetTaskId) {
                throw new IllegalStateException(
                        "workspace target became unavailable");
            }
            final ShellDesktopFocusController.CommitBarrier commitBarrier =
                    mFocusController.captureCommitBarrier();
            applyPhysicalOrder(command, physicalOrder);
            mTaskSampleRequester.run();
            if (!mFocusController.convergeAfterCommit(
                    command.targetTaskId,
                    commitBarrier,
                    mTaskSampleRequester)) {
                return Result.failure(
                        appliedTaskCount,
                        "input focus did not converge for task "
                                + command.targetTaskId);
            }
            Log.d(TAG, "completed " + command.operationName()
                    + " display=" + command.displayId
                    + " target=" + command.targetTaskId
                    + " tasks=" + appliedTaskCount);
            return Result.success(appliedTaskCount);
        } catch (ReflectiveOperationException | RuntimeException error) {
            final String message = usefulMessage(error);
            Log.w(TAG, "workspace command failed: " + message, error);
            return Result.failure(appliedTaskCount, message);
        }
    }

    private void applyPhysicalOrder(
            final DesktopWorkspaceCommand command,
            final int[] physicalOrder) throws ReflectiveOperationException {
        final int targetTaskId = command.targetTaskId;
        final ShellFullscreenTaskArea.FocusResult focusResult =
                mFullscreenTaskArea.focusStack(
                        mService, command.displayId, physicalOrder);
        if (focusResult == ShellFullscreenTaskArea.FocusResult.NOT_HANDLED) {
            final Object targetTask = HiddenTaskApi.requireTask(
                    mService, command.displayId, targetTaskId);
            final int[] fallbackTaskIds =
                    HiddenTaskApi.getTaskWindowingMode(targetTask)
                            == WINDOWING_MODE_FULLSCREEN
                                    ? new int[]{targetTaskId}
                                    : physicalOrder;
            TaskWindowingCommand.focusTasks(
                    mService, command.displayId, fallbackTaskIds);
            mForegroundReporter.reportForTask(targetTaskId);
        } else {
            mForegroundReporter.reportSessionForeground(
                    focusResult
                            == ShellFullscreenTaskArea.FocusResult
                                    .SESSION_FOREGROUND);
        }
        if (command.presentsDesktop()
                && !mFullscreenTaskArea.concealForShowDesktop(
                        command.displayId)) {
            throw new IllegalStateException(
                    "fullscreen planes could not be concealed");
        }
    }

    private static String usefulMessage(final Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
