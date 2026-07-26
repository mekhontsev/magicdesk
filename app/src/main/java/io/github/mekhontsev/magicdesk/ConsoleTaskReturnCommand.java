package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.graphics.Rect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class ConsoleTaskReturnCommand {
    private static final int PHONE_DISPLAY_ID = 0;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final long MOVE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long MOVE_POLL_MILLIS = 25L;

    private ConsoleTaskReturnCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 1) {
            System.err.println("usage: ConsoleTaskReturnCommand <console-display-id>");
            System.exit(64);
            return;
        }

        try {
            final int sourceDisplayId = parseDisplayId(args[0]);
            final Object service = getActivityTaskManagerService();
            final List<Integer> taskIds = findApplicationTasks(service, sourceDisplayId);
            Collections.reverse(taskIds);
            int moved = 0;
            for (final int taskId : taskIds) {
                moveRootTask(taskId, PHONE_DISPLAY_ID);
                final Object task = awaitTask(service, PHONE_DISPLAY_ID, taskId);
                normalizePhoneTask(service, task);
                moved++;
            }
            System.out.println("tasks-returned=" + moved
                    + " from=" + sourceDisplayId
                    + " to=" + PHONE_DISPLAY_ID);
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("task return failed: " + cause);
            System.exit(1);
        }
    }

    private static List<Integer> findApplicationTasks(final Object service,
            final int displayId) throws ReflectiveOperationException {
        final Object result = service.getClass()
                .getMethod("getTasks", Integer.TYPE, Boolean.TYPE, Boolean.TYPE, Integer.TYPE)
                .invoke(service, Integer.valueOf(100), Boolean.FALSE, Boolean.TRUE,
                        Integer.valueOf(displayId));
        final List<Integer> taskIds = new ArrayList<>();
        if (!(result instanceof List)) {
            return taskIds;
        }
        for (final Object task : (List<?>) result) {
            if (getActivityType(task) != ACTIVITY_TYPE_STANDARD
                    || isMagicDeskTask(task)) {
                continue;
            }
            taskIds.add(Integer.valueOf(getIntField(task, "taskId")));
        }
        return taskIds;
    }

    private static boolean isMagicDeskTask(final Object task)
            throws ReflectiveOperationException {
        final ComponentName topActivity =
                (ComponentName) task.getClass().getField("topActivity").get(task);
        if (isMagicDeskPackage(topActivity)) {
            return true;
        }
        final ComponentName baseActivity =
                (ComponentName) task.getClass().getField("baseActivity").get(task);
        return isMagicDeskPackage(baseActivity);
    }

    private static boolean isMagicDeskPackage(final ComponentName component) {
        return component != null
                && component.getPackageName().toLowerCase().endsWith(".magicdesk");
    }

    private static int getActivityType(final Object task)
            throws ReflectiveOperationException {
        final Object configuration =
                task.getClass().getField("configuration").get(task);
        final Object windowConfiguration = configuration.getClass()
                .getField("windowConfiguration").get(configuration);
        return ((Integer) windowConfiguration.getClass()
                .getMethod("getActivityType").invoke(windowConfiguration)).intValue();
    }

    private static void moveRootTask(final int taskId, final int displayId)
            throws IOException {
        final Process process = new ProcessBuilder(
                "/system/bin/cmd", "activity", "display", "move-stack",
                Integer.toString(taskId), Integer.toString(displayId))
                .redirectErrorStream(true)
                .start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(line);
            }
        }
        final int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("task move interrupted", e);
        }
        if (exitCode != 0) {
            throw new IOException("move task " + taskId + " failed "
                    + exitCode + ": " + output);
        }
    }

    private static Object awaitTask(final Object service, final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final long deadline = System.nanoTime() + MOVE_TIMEOUT_NANOS;
        do {
            final Object task = findTask(service, displayId, taskId);
            if (task != null) {
                return task;
            }
            try {
                Thread.sleep(MOVE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("task move interrupted", e);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(
                "task " + taskId + " did not move to display " + displayId);
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
        return null;
    }

    private static void normalizePhoneTask(final Object service, final Object task)
            throws ReflectiveOperationException {
        final Object taskToken = task.getClass().getField("token").get(task);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(transaction, taskToken,
                        Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, new Rect());
        transactionClass.getMethod("setDensityDpi", tokenClass, Integer.TYPE)
                .invoke(transaction, taskToken, Integer.valueOf(0));
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass, transaction, tokenClass, taskToken, true);
        SyncWindowContainerTransaction.apply(service, transactionClass, transaction);
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

    private static int parseDisplayId(final String value) {
        final int displayId = Integer.parseInt(value);
        if (displayId <= 0) {
            throw new IllegalArgumentException("invalid Console display id");
        }
        return displayId;
    }
}
