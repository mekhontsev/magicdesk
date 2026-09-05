package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

/** Moves a task across displays without publishing it in the wrong mode. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskFullscreenMoveCommand {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final long TASK_TIMEOUT_MILLIS = 5_000L;
    private static final long TASK_POLL_MILLIS = 25L;

    private TaskFullscreenMoveCommand() {
    }

    static String createMoveCommand(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final int densityDpi) {
        validate(taskId, sourceDisplayId, targetDisplayId);
        return AppProcessCommand.run(
                TaskFullscreenMoveCommand.class.getName(),
                taskId + " " + sourceDisplayId
                        + " " + targetDisplayId
                        + " " + densityDpi);
    }

    public static void main(final String[] args) {
        if (args.length != 4) {
            System.err.println("usage: TaskFullscreenMoveCommand "
                    + "<task-id> "
                    + "<source-display-id> <target-display-id> <density-dpi>");
            System.exit(64);
            return;
        }
        try {
            final int taskId = parseNonNegative(args[0], "task id");
            final int sourceDisplayId = parseNonNegative(
                    args[1], "source display id");
            final int targetDisplayId = parseNonNegative(
                    args[2], "target display id");
            final int densityDpi = Integer.parseInt(args[3]);
            moveTask(
                    HiddenTaskApi.getService(),
                    taskId,
                    sourceDisplayId,
                    targetDisplayId,
                    densityDpi);
            System.out.println("task-fullscreen-move=" + taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("fullscreen task move failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    static void moveTask(
            final Object service,
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final int densityDpi) throws ReflectiveOperationException {
        validate(taskId, sourceDisplayId, targetDisplayId);
        if (!DesktopTaskDensity.isValid(densityDpi)) {
            throw new IllegalArgumentException("invalid task density");
        }
        final Object originalTask = HiddenTaskApi.requireTask(
                service, sourceDisplayId, taskId);
        final int originalWindowingMode =
                HiddenTaskApi.getTaskWindowingMode(originalTask);
        final Object originalWindowConfiguration =
                HiddenTaskApi.getWindowConfiguration(originalTask);
        final Rect originalBounds = new Rect(
                (Rect) originalWindowConfiguration.getClass()
                        .getMethod("getBounds")
                        .invoke(originalWindowConfiguration));
        boolean taskHidden = false;
        try {
            // Commit the target mode while the task still belongs to its
            // source display. Only a hidden task crosses the display boundary.
            taskHidden = true;
            ShellPreparedTaskTransition.prepareFullscreen(
                    service, sourceDisplayId, taskId);
            waitForTaskState(
                    service,
                    sourceDisplayId,
                    taskId,
                    WINDOWING_MODE_FULLSCREEN,
                    false);
            TaskDisplayAreaLaunchCommand.moveExistingTaskAsFullscreen(
                    service, taskId, sourceDisplayId, targetDisplayId, densityDpi);
            waitForTaskState(
                    service,
                    targetDisplayId,
                    taskId,
                    WINDOWING_MODE_FULLSCREEN,
                    true);
            // Do not let a following launcher Intent race WM's finish-surface
            // transaction. This is the existing framework event barrier, not
            // a delay or another task/focus submission.
            FrameworkWindowCommitBarrier.awaitSystemTransitions();
            taskHidden = false;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (taskHidden) {
                try {
                    TaskDisplayAreaLaunchCommand.restoreFailedTaskMove(
                            service,
                            taskId,
                            sourceDisplayId,
                            originalWindowingMode,
                            originalBounds);
                } catch (ReflectiveOperationException
                        | RuntimeException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
            }
            throw error;
        }
    }

    private static void waitForTaskState(
            final Object service,
            final int displayId,
            final int taskId,
            final int windowingMode,
            final boolean visible) throws ReflectiveOperationException {
        final FrameworkTaskSnapshot task =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_WINDOWING_MODE,
                        TASK_TIMEOUT_MILLIS,
                        TASK_POLL_MILLIS,
                        () -> FrameworkTaskSnapshotSource.findTask(
                                service, displayId, taskId),
                        current -> current != null
                                && current.windowingMode == windowingMode
                                && current.visible == visible);
        if (task != null
                && task.windowingMode == windowingMode
                && task.visible == visible) {
            return;
        }
        throw new IllegalStateException(
                "task " + taskId + " did not settle on display "
                        + displayId + " mode=" + windowingMode
                        + " visible=" + visible);
    }

    private static void validate(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) {
        if (taskId < 0
                || sourceDisplayId < 0 || targetDisplayId < 0
                || sourceDisplayId == targetDisplayId) {
            throw new IllegalArgumentException("invalid fullscreen task move");
        }
    }

    private static int parseNonNegative(
            final String value,
            final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }

    private static String usefulMessage(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getSimpleName() : message;
    }
}
