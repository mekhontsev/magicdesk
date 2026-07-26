package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Establishes final fullscreen geometry and recreates the client with those parameters.
 *
 * <p>Nubia can retain a desktop caption inset in the client after the task has already
 * become fullscreen. The temporary density override requests an Activity relaunch after
 * fullscreen bounds and caption exclusion are committed. Restoring density inheritance
 * requires a second configuration transaction.</p>
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskFullscreenTransitionCommand {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int TRANSIT_CHANGE = 6;
    private static final long TRANSITION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long TRANSITION_POLL_MILLIS = 20L;

    private TaskFullscreenTransitionCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 2) {
            System.err.println(
                    "usage: TaskFullscreenTransitionCommand <display-id> <task-id>");
            System.exit(64);
            return;
        }

        try {
            final int displayId = parseInt(args[0], "display id");
            final int taskId = parseInt(args[1], "task id");
            applyFullscreen(displayId, taskId);
            System.out.println("task-fullscreen=" + taskId + " display=" + displayId
                    + " client=recreated");
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("fullscreen transition failed: " + cause);
            System.exit(1);
        }
    }

    private static void applyFullscreen(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        final Object service = getActivityTaskManagerService();
        final Object task = findTask(service, displayId, taskId);
        final Object taskToken = task.getClass().getField("token").get(task);
        final Object configuration = task.getClass().getField("configuration").get(task);
        final int densityDpi = getIntField(configuration, "densityDpi");
        if (densityDpi <= 1 || densityDpi == Integer.MAX_VALUE) {
            throw new IllegalStateException("invalid task density " + densityDpi);
        }

        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        boolean temporaryDensityApplied = false;
        try {
            final Object fullscreenTransaction =
                    transactionClass.getConstructor().newInstance();
            transactionClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(fullscreenTransaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(fullscreenTransaction, taskToken, new Rect());
            transactionClass.getMethod("setDensityDpi", tokenClass, Integer.TYPE)
                    .invoke(fullscreenTransaction, taskToken,
                            Integer.valueOf(densityDpi + 1));
            transactionClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                    .invoke(fullscreenTransaction, taskToken, Boolean.TRUE);
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass, fullscreenTransaction, tokenClass, taskToken, true);

            startTransition(transactionClass, fullscreenTransaction);
            temporaryDensityApplied = true;

            awaitFullscreen(service, displayId, taskId);
            applyDensity(service, transactionClass, tokenClass, taskToken, 0);
            temporaryDensityApplied = false;
        } finally {
            if (temporaryDensityApplied) {
                applyDensity(service, transactionClass, tokenClass, taskToken, 0);
            }
        }
    }

    static void startTransition(final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        final Class<?> organizerClass = Class.forName("android.window.WindowOrganizer");
        final Object organizer = organizerClass.getConstructor().newInstance();
        organizerClass.getMethod("startNewTransition", Integer.TYPE, transactionClass)
                .invoke(organizer, Integer.valueOf(TRANSIT_CHANGE), transaction);
    }

    static void awaitFullscreen(final Object service, final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final long deadline = System.nanoTime() + TRANSITION_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            final Object task = findTask(service, displayId, taskId);
            final Object configuration =
                    task.getClass().getField("configuration").get(task);
            final Object windowConfiguration = configuration.getClass()
                    .getField("windowConfiguration").get(configuration);
            final int windowingMode = ((Integer) windowConfiguration.getClass()
                    .getMethod("getWindowingMode").invoke(windowConfiguration)).intValue();
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

    private static void applyDensity(final Object service,
            final Class<?> transactionClass, final Class<?> tokenClass,
            final Object taskToken, final int densityDpi)
            throws ReflectiveOperationException {
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("setDensityDpi", tokenClass, Integer.TYPE)
                .invoke(transaction, taskToken, Integer.valueOf(densityDpi));
        SyncWindowContainerTransaction.apply(service, transactionClass, transaction);
    }

    private static Object findTask(final Object service, final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final Object result = service.getClass()
                .getMethod("getTasks", Integer.TYPE, Boolean.TYPE, Boolean.TYPE, Integer.TYPE)
                .invoke(service, Integer.valueOf(100), Boolean.FALSE, Boolean.TRUE,
                        Integer.valueOf(displayId));
        if (result instanceof List) {
            for (final Object task : (List<?>) result) {
                if (getIntField(task, "taskId") == taskId) {
                    return task;
                }
            }
        }
        throw new IllegalStateException("task " + taskId + " not found on display "
                + displayId);
    }

    private static Object getActivityTaskManagerService()
            throws ReflectiveOperationException {
        final Class<?> activityTaskManager = Class.forName("android.app.ActivityTaskManager");
        final Method getService = activityTaskManager.getDeclaredMethod("getService");
        getService.setAccessible(true);
        return getService.invoke(null);
    }

    private static int getIntField(final Object target, final String name)
            throws ReflectiveOperationException {
        final Field field = target.getClass().getField(name);
        return field.getInt(target);
    }

    private static int parseInt(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }
}
