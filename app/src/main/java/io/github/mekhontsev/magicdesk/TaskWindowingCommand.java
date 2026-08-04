package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

import java.lang.reflect.Method;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskWindowingCommand {
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private TaskWindowingCommand() {
    }

    public static void main(final String[] args) {
        try {
            if (args.length == 7 && "freeform".equals(args[0])) {
                setFreeform(parseInt(args[1], "display id"),
                        parseInt(args[2], "task id"),
                        parseInt(args[3], "left"), parseInt(args[4], "top"),
                        parseInt(args[5], "right"), parseInt(args[6], "bottom"));
                return;
            }
            if (args.length == 3 && "desktop-host".equals(args[0])) {
                setDesktopHost(parseInt(args[1], "display id"),
                        parseInt(args[2], "task id"));
                return;
            }
            if (args.length == 4 && "minimize".equals(args[0])) {
                minimize(parseInt(args[1], "display id"),
                        parseInt(args[2], "task id"),
                        parseInt(args[3], "focus task id"));
                return;
            }
            if (args.length == 3 && "restore".equals(args[0])) {
                restore(parseInt(args[1], "display id"),
                        parseInt(args[2], "task id"));
                return;
            }
            if (args.length == 3 && "send-behind".equals(args[0])) {
                sendBehind(parseInt(args[1], "display id"),
                        parseInt(args[2], "task id"));
                return;
            }
            if (args.length >= 3 && "restore-stack".equals(args[0])) {
                final int[] taskIds = new int[args.length - 2];
                for (int index = 2; index < args.length; index++) {
                    taskIds[index - 2] = parseInt(args[index], "task id");
                }
                restoreStack(parseInt(args[1], "display id"), taskIds);
                return;
            }
            System.err.println("usage: TaskWindowingCommand "
                    + "<freeform display task left top right bottom"
                    + "|desktop-host display task"
                    + "|minimize display task focus-task|restore display task"
                    + "|send-behind display task"
                    + "|restore-stack display task...>");
            System.exit(64);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("task windowing command failed: " + cause);
            System.exit(1);
        }
    }

    private static void setFreeform(final int displayId, final int taskId,
            final int left, final int top, final int right, final int bottom)
            throws ReflectiveOperationException {
        if (right <= left || bottom <= top) {
            throw new IllegalArgumentException("invalid bounds");
        }
        apply(displayId, taskId, WINDOWING_MODE_FREEFORM,
                new Rect(left, top, right, bottom));
        System.out.println("task-freeform=" + taskId);
    }

    private static void setDesktopHost(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        TaskFullscreenTransitionCommand.applyFullscreen(
                displayId, taskId, true);
        System.out.println("desktop-host=" + taskId);
    }

    private static void minimize(final int displayId, final int taskId,
            final int focusTaskId)
            throws ReflectiveOperationException {
        if (taskId == focusTaskId) {
            throw new IllegalArgumentException("minimized and focused task match");
        }
        final Object service = HiddenTaskApi.getService();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Object focusTaskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, focusTaskId);
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(transaction, focusTaskToken, Boolean.TRUE, Boolean.TRUE);
        SyncWindowContainerTransaction.apply(
                service, transactionClass, transaction);
        TaskControlCommand.setFocusedTask(service, focusTaskId);
        System.out.println("task-minimized=" + taskId
                + " focused=" + focusTaskId);
    }

    private static void restore(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        restoreStack(displayId, new int[]{taskId});
    }

    private static void sendBehind(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        final Object service = HiddenTaskApi.getService();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        SyncWindowContainerTransaction.apply(service, transactionClass, transaction);
        System.out.println("task-sent-behind=" + taskId);
    }

    private static void restoreStack(final int displayId, final int[] taskIds)
            throws ReflectiveOperationException {
        final Object service = HiddenTaskApi.getService();
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        final Method reorder = transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE);
        for (final int taskId : taskIds) {
            reorder.invoke(
                    transaction,
                    HiddenTaskApi.requireTaskToken(
                            service, displayId, taskId),
                    Boolean.TRUE, Boolean.TRUE);
        }
        SyncWindowContainerTransaction.apply(service, transactionClass, transaction);
        System.out.println("task-stack-restored=" + taskIds.length);
    }

    private static void apply(final int displayId, final int taskId,
            final int windowingMode, final Rect bounds) throws ReflectiveOperationException {
        final Object service = HiddenTaskApi.getService();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(transaction, taskToken, Integer.valueOf(windowingMode));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, bounds);
        transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.TRUE, Boolean.TRUE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass, transaction, tokenClass, taskToken, false);
        TaskFullscreenTransitionCommand.startTransition(
                transactionClass, transaction);
    }

    private static int parseInt(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }
}
