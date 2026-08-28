package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

/** Establishes fullscreen geometry and optionally refreshes a stale caption inset. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskFullscreenTransitionCommand {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final long TRANSITION_TIMEOUT_MILLIS = 3_000L;
    private static final long TRANSITION_POLL_MILLIS = 20L;

    private TaskFullscreenTransitionCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 3) {
            System.err.println(
                    "usage: TaskFullscreenTransitionCommand "
                            + "<display-id> <task-id> <refresh-caption>");
            System.exit(64);
            return;
        }

        try {
            final int displayId = parseInt(args[0], "display id");
            final int taskId = parseInt(args[1], "task id");
            final boolean refreshCaption = parseFlag(
                    args[2], "refresh caption");
            final boolean captionRefreshed =
                    applyFullscreen(
                            displayId, taskId, false, refreshCaption);
            System.out.println("task-fullscreen=" + taskId + " display=" + displayId
                    + " caption=" + (!refreshCaption
                            ? "not-required"
                            : (captionRefreshed
                                    ? "refreshed" : "not-present")));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("fullscreen transition failed: " + cause);
            System.exit(1);
        }
    }

    static boolean applyFullscreen(final int displayId, final int taskId,
            final boolean forceTranslucent,
            final boolean refreshCaption)
            throws ReflectiveOperationException {
        final int captionSourceId = refreshCaption
                ? TaskCaptionInsetsRefresher.captureCaptionSourceId(taskId)
                : TaskLocalInsetsSourceParser.NO_SOURCE_ID;
        final Object service = HiddenTaskApi.getService();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);

        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object fullscreenTransaction = windowing.newTransaction();
        windowing.setWindowingMode(
                fullscreenTransaction, taskToken, WINDOWING_MODE_FULLSCREEN);
        windowing.setBounds(fullscreenTransaction, taskToken, new Rect());
        windowing.reorder(fullscreenTransaction, taskToken, true);
        windowing.setForceTranslucent(
                fullscreenTransaction, taskToken, forceTranslucent);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                fullscreenTransaction, taskToken, true);

        ShellWindowTransitionExecutor.startForShellAdoption(
                displayId,
                ShellWindowTransitionExecutor.SystemTransition.CHANGE,
                transactionClass,
                fullscreenTransaction,
                "enter-fullscreen");
        awaitFullscreen(service, displayId, taskId);
        return refreshCaptionIfRequested(
                service,
                displayId,
                taskId,
                refreshCaption,
                captionSourceId);
    }

    static boolean refreshCaptionIfRequested(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean refreshCaption,
            final int captionSourceId) {
        if (!refreshCaption
                || captionSourceId
                        == TaskLocalInsetsSourceParser.NO_SOURCE_ID) {
            return false;
        }
        try {
            TaskCaptionInsetsRefresher.refreshTask(
                    service, displayId, taskId, captionSourceId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.printf("caption source refresh failed: id=%08x: %s%n",
                    captionSourceId, e);
            return false;
        }
    }

    static void awaitFullscreen(final Object service, final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final FrameworkTaskSnapshot task =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_WINDOWING_MODE,
                        TRANSITION_TIMEOUT_MILLIS,
                        TRANSITION_POLL_MILLIS,
                        () -> FrameworkTaskSnapshotSource.findTask(
                                service, displayId, taskId),
                        current -> current != null
                                && current.windowingMode
                                        == WINDOWING_MODE_FULLSCREEN);
        if (task != null
                && task.windowingMode == WINDOWING_MODE_FULLSCREEN) {
            return;
        }
        throw new IllegalStateException("fullscreen transition timed out");
    }

    private static int parseInt(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }

    private static boolean parseFlag(
            final String value, final String label) {
        final int parsed = parseInt(value, label);
        if (parsed > 1) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed == 1;
    }
}
