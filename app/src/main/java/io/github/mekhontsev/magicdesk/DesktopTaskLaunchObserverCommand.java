package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.graphics.Rect;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reports the first front-state of one task from the shell UID. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class DesktopTaskLaunchObserverCommand {
    static final String READY = "MAGICDESK_TASK_LAUNCH_READY";
    static final String OBSERVED = "MAGICDESK_TASK_LAUNCH_OBSERVED";
    static final String TIMEOUT = "MAGICDESK_TASK_LAUNCH_TIMEOUT";

    private static final long TIMEOUT_SECONDS = 10L;

    private DesktopTaskLaunchObserverCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 4) {
            System.err.println("usage: DesktopTaskLaunchObserverCommand "
                    + "<task-id|-1> <package> <activity-class> "
                    + "<display-id|-1>");
            System.exit(64);
            return;
        }

        int exitCode = 0;
        TaskStackListener listener = null;
        Object service = null;
        try {
            final int expectedTaskId = parseTaskId(args[0]);
            final int expectedDisplayId = parseDisplayId(args[3]);
            if (!PackageNameValidator.isSafe(args[1])
                    || !AppLaunchTarget.isSafeClassName(args[2])
                    || args[2].isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid task launch component");
            }
            final ComponentName expectedComponent =
                    new ComponentName(args[1], args[2]);
            service = HiddenTaskApi.getService();
            final CountDownLatch observed = new CountDownLatch(1);
            final AtomicBoolean published = new AtomicBoolean();
            listener = new TaskStackListener() {
                @Override
                public void onTaskMovedToFront(
                        final ActivityManager.RunningTaskInfo taskInfo) {
                    if (taskInfo == null
                            || !matches(taskInfo, expectedTaskId,
                                    expectedComponent, expectedDisplayId)
                            || !published.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        System.out.println(format(taskInfo));
                    } catch (ReflectiveOperationException
                            | RuntimeException error) {
                        System.out.println(OBSERVED + "\terror\t"
                                + usefulMessage(error));
                    } finally {
                        System.out.flush();
                        observed.countDown();
                    }
                }
            };
            HiddenTaskApi.registerTaskStackListener(service, listener);
            System.out.println(READY);
            System.out.flush();
            if (!observed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.out.println(TIMEOUT);
                System.out.flush();
                exitCode = 2;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("task launch observation interrupted");
            exitCode = 1;
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("task launch observation failed: "
                    + usefulMessage(error));
            exitCode = 1;
        } finally {
            unregister(service, listener);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static boolean matches(
            final ActivityManager.RunningTaskInfo task,
            final int expectedTaskId,
            final ComponentName expectedComponent,
            final int expectedDisplayId) {
        if (expectedTaskId >= 0 && task.taskId != expectedTaskId) {
            return false;
        }
        if (expectedDisplayId >= 0
                && HiddenTaskApi.getTaskDisplayId(task)
                        != expectedDisplayId) {
            return false;
        }
        return expectedComponent.equals(task.topActivity)
                || expectedComponent.equals(task.baseActivity);
    }

    private static String format(
            final ActivityManager.RunningTaskInfo task)
            throws ReflectiveOperationException {
        final int windowingMode = HiddenTaskApi.getTaskWindowingMode(task);
        final Object windowConfiguration =
                HiddenTaskApi.getWindowConfiguration(task);
        final Rect bounds = new Rect((Rect) windowConfiguration.getClass()
                .getMethod("getBounds").invoke(windowConfiguration));
        return OBSERVED
                + "\t" + task.taskId
                + "\t" + HiddenTaskApi.getTaskDisplayId(task)
                + "\t" + windowingMode
                + "\t" + bounds.left
                + "\t" + bounds.top
                + "\t" + bounds.right
                + "\t" + bounds.bottom;
    }

    private static void unregister(
            final Object service,
            final TaskStackListener listener) {
        if (service == null || listener == null) {
            return;
        }
        try {
            HiddenTaskApi.unregisterTaskStackListener(service, listener);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The observer process is terminating and owns no persistent state.
        }
    }

    private static int parseTaskId(final String value) {
        final int taskId = Integer.parseInt(value);
        if (taskId < -1) {
            throw new IllegalArgumentException("invalid task id");
        }
        return taskId;
    }

    private static int parseDisplayId(final String value) {
        final int displayId = Integer.parseInt(value);
        if (displayId < -1) {
            throw new IllegalArgumentException("invalid display id");
        }
        return displayId;
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }
}
