package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

import java.util.concurrent.TimeUnit;

/** Establishes fullscreen geometry and optionally refreshes a stale caption inset. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskFullscreenTransitionCommand {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int TRANSIT_CHANGE = 6;
    private static final long TRANSITION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
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

        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object fullscreenTransaction =
                transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(fullscreenTransaction, taskToken,
                        Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(fullscreenTransaction, taskToken, new Rect());
        transactionClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                .invoke(fullscreenTransaction, taskToken, Boolean.TRUE);
        transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE)
                .invoke(fullscreenTransaction, taskToken,
                        Boolean.valueOf(forceTranslucent));
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass, fullscreenTransaction, tokenClass, taskToken, true);

        startTransition(transactionClass, fullscreenTransaction);
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

    static void startTransition(final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        startTransition(TRANSIT_CHANGE, transactionClass, transaction);
    }

    static void startTransition(final int transitionType,
            final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        final Class<?> organizerClass = Class.forName("android.window.WindowOrganizer");
        final Object organizer = organizerClass.getConstructor().newInstance();
        organizerClass.getMethod("startNewTransition", Integer.TYPE, transactionClass)
                .invoke(organizer, Integer.valueOf(transitionType), transaction);
    }

    static void awaitFullscreen(final Object service, final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final long deadline = System.nanoTime() + TRANSITION_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            final int windowingMode =
                    HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode");
            if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
                return;
            }
            try {
                Thread.sleep(TRANSITION_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fullscreen transition interrupted", e);
            }
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
