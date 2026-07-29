package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

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
            if (args.length == 3 && "minimize".equals(args[0])) {
                minimize(parseInt(args[1], "display id"),
                        parseInt(args[2], "task id"));
                return;
            }
            if (args.length == 3 && "restore".equals(args[0])) {
                restore(parseInt(args[1], "display id"),
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
            if (args.length >= 4 && "switch-space".equals(args[0])) {
                switchSpace(args);
                return;
            }
            System.err.println("usage: TaskWindowingCommand "
                    + "<freeform display task left top right bottom"
                    + "|minimize display task|restore display task"
                    + "|restore-stack display task..."
                    + "|switch-space display hide-count hide... restore-count restore...>");
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

    private static void minimize(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        final Object service = getActivityTaskManagerService();
        final Object taskToken = findTaskToken(service, displayId, taskId);
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        applyTransaction(service, transactionClass, transaction);
        System.out.println("task-minimized=" + taskId);
    }

    private static void restore(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        restoreStack(displayId, new int[]{taskId});
    }

    private static void restoreStack(final int displayId, final int[] taskIds)
            throws ReflectiveOperationException {
        final Object service = getActivityTaskManagerService();
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        final Method reorder = transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE);
        for (final int taskId : taskIds) {
            reorder.invoke(transaction, findTaskToken(service, displayId, taskId),
                    Boolean.TRUE, Boolean.TRUE);
        }
        SyncWindowContainerTransaction.apply(service, transactionClass, transaction);
        System.out.println("task-stack-restored=" + taskIds.length);
    }

    private static void switchSpace(final String[] args)
            throws ReflectiveOperationException {
        final int displayId = parseInt(args[1], "display id");
        final int hideCount = parseInt(args[2], "hide count");
        final int restoreCountIndex = 3 + hideCount;
        if (restoreCountIndex >= args.length) {
            throw new IllegalArgumentException("missing restore count");
        }
        final int restoreCount =
                parseInt(args[restoreCountIndex], "restore count");
        if (restoreCountIndex + 1 + restoreCount != args.length) {
            throw new IllegalArgumentException("invalid task counts");
        }

        final Object service = getActivityTaskManagerService();
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        final Method reorderToBack = transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE);
        final Method reorderToFront = transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE);
        for (int index = 0; index < hideCount; index++) {
            reorderToBack.invoke(
                    transaction,
                    findTaskToken(
                            service, displayId,
                            parseInt(args[3 + index], "hidden task id")),
                    Boolean.FALSE);
        }
        for (int index = restoreCount - 1; index >= 0; index--) {
            reorderToFront.invoke(
                    transaction,
                    findTaskToken(
                            service, displayId,
                            parseInt(
                                    args[restoreCountIndex + 1 + index],
                                    "restored task id")),
                    Boolean.TRUE, Boolean.TRUE);
        }
        SyncWindowContainerTransaction.apply(
                service, transactionClass, transaction);
        System.out.println("desktop-space-switched=" + restoreCount);
    }

    private static void apply(final int displayId, final int taskId,
            final int windowingMode, final Rect bounds) throws ReflectiveOperationException {
        final Object service = getActivityTaskManagerService();
        final Object taskToken = findTaskToken(service, displayId, taskId);
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(transaction, taskToken, Integer.valueOf(windowingMode));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, bounds);
        transactionClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        SyncWindowContainerTransaction.apply(service, transactionClass, transaction);
        TaskControlCommand.moveTaskToFront(service, taskId);
    }

    private static void applyTransaction(final Object service,
            final Class<?> transactionClass, final Object transaction)
            throws ReflectiveOperationException {
        final Object controller = service.getClass()
                .getMethod("getWindowOrganizerController").invoke(service);
        controller.getClass().getMethod("applyTransaction", transactionClass)
                .invoke(controller, transaction);
    }

    private static Object findTaskToken(final Object service, final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final Object result = service.getClass()
                .getMethod("getTasks", Integer.TYPE, Boolean.TYPE, Boolean.TYPE, Integer.TYPE)
                .invoke(service, Integer.valueOf(100), Boolean.FALSE, Boolean.TRUE,
                        Integer.valueOf(displayId));
        if (result instanceof List) {
            for (final Object task : (List<?>) result) {
                if (getIntField(task, "taskId") == taskId) {
                    return task.getClass().getField("token").get(task);
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
