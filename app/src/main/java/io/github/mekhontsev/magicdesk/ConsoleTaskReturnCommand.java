package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.graphics.Rect;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
            final Object service = HiddenTaskApi.getService();
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
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task :
                HiddenTaskApi.getTasks(service, displayId)) {
            if (getActivityType(task) != ACTIVITY_TYPE_STANDARD
                    || isMagicDeskTask(task)) {
                continue;
            }
            taskIds.add(Integer.valueOf(
                    HiddenTaskApi.getIntField(task, "taskId")));
        }
        return taskIds;
    }

    private static boolean isMagicDeskTask(final Object task)
            throws ReflectiveOperationException {
        final ComponentName topActivity =
                (ComponentName) HiddenTaskApi.getField(
                        task, "topActivity");
        if (isMagicDeskPackage(topActivity)) {
            return true;
        }
        final ComponentName baseActivity =
                (ComponentName) HiddenTaskApi.getField(
                        task, "baseActivity");
        return isMagicDeskPackage(baseActivity);
    }

    private static boolean isMagicDeskPackage(final ComponentName component) {
        return component != null
                && component.getPackageName()
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".magicdesk");
    }

    private static int getActivityType(final Object task)
            throws ReflectiveOperationException {
        return HiddenTaskApi.getWindowConfigurationValue(
                task, "getActivityType");
    }

    private static void moveRootTask(final int taskId, final int displayId)
            throws IOException {
        final Process process = new ProcessBuilder(
                "/system/bin/cmd", "activity", "display", "move-stack",
                Integer.toString(taskId), Integer.toString(displayId))
                .redirectErrorStream(true)
                .start();
        try {
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(process);
            if (result.exitCode != 0) {
                throw new IOException("move task " + taskId + " failed "
                        + result.exitCode + ": " + result.output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("task move interrupted", e);
        } finally {
            process.destroy();
        }
    }

    private static Object awaitTask(final Object service, final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final long deadline = System.nanoTime() + MOVE_TIMEOUT_NANOS;
        do {
            final Object task =
                    HiddenTaskApi.findTask(service, displayId, taskId);
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

    private static void normalizePhoneTask(final Object service, final Object task)
            throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.getField(task, "token");
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

    private static int parseDisplayId(final String value) {
        final int displayId = Integer.parseInt(value);
        if (displayId <= 0) {
            throw new IllegalArgumentException("invalid Console display id");
        }
        return displayId;
    }
}
