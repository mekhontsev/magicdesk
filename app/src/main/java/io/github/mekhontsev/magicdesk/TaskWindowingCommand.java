package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

import java.lang.reflect.Method;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskWindowingCommand {
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int TRANSIT_TO_FRONT = 3;

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
            if (args.length == 7 && "bounds".equals(args[0])) {
                setBounds(parseInt(args[1], "display id"),
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
            if (args.length >= 3 && "focus".equals(args[0])) {
                focus(parseInt(args[1], "display id"), args);
                return;
            }
            if (args.length >= 7
                    && (args.length - 2) % 5 == 0
                    && "restore-layout".equals(args[0])) {
                restoreLayout(parseInt(args[1], "display id"), args);
                return;
            }
            System.err.println("usage: TaskWindowingCommand "
                    + "<freeform|bounds display task left top right bottom"
                    + "|desktop-host display task"
                    + "|minimize display task focus-task|restore display task"
                    + "|focus display task..."
                    + "|restore-layout display task left top right bottom...>");
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
        applyFreeform(
                HiddenTaskApi.getService(),
                displayId,
                taskId,
                new Rect(left, top, right, bottom));
        System.out.println("task-freeform=" + taskId);
    }

    private static void setDesktopHost(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        TaskFullscreenTransitionCommand.applyFullscreen(
                displayId, taskId, true);
        System.out.println("desktop-host=" + taskId);
    }

    private static void setBounds(
            final int displayId,
            final int taskId,
            final int left,
            final int top,
            final int right,
            final int bottom) throws ReflectiveOperationException {
        if (right <= left || bottom <= top) {
            throw new IllegalArgumentException("invalid bounds");
        }
        final Object service = HiddenTaskApi.getService();
        HiddenTaskApi.requireTask(service, displayId, taskId);
        // Match `am task resize`: the task service coordinates this request
        // with an in-flight native caption transition. A direct synchronous
        // WCT can race WMShell and leave its resize veil attached.
        service.getClass().getMethod(
                "resizeTask", Integer.TYPE, Rect.class, Integer.TYPE)
                .invoke(
                        service,
                        Integer.valueOf(taskId),
                        new Rect(left, top, right, bottom),
                        Integer.valueOf(0));
        System.out.println("task-bounds=" + taskId);
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
        System.out.println("task-minimized=" + taskId
                + " focused=" + focusTaskId);
    }

    private static void restore(final int displayId, final int taskId)
            throws ReflectiveOperationException {
        restoreStack(displayId, new int[]{taskId});
    }

    private static void restoreStack(final int displayId, final int[] taskIds)
            throws ReflectiveOperationException {
        focusTasks(HiddenTaskApi.getService(), displayId, taskIds);
        System.out.println("task-stack-restored=" + taskIds.length);
    }

    private static void focus(
            final int displayId,
            final String[] args) throws ReflectiveOperationException {
        final int[] taskIds = new int[args.length - 2];
        for (int index = 0; index < taskIds.length; index++) {
            taskIds[index] = parseInt(args[index + 2], "task id");
        }
        focusTasks(HiddenTaskApi.getService(), displayId, taskIds);
        System.out.println("task-stack-focused=" + taskIds.length);
    }

    static void focusTasks(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        if (taskIds == null || taskIds.length == 0) {
            throw new IllegalArgumentException("missing tasks to focus");
        }
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        final Method reorderTask = transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE);
        for (final int taskId : taskIds) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            reorderTask.invoke(
                    transaction, taskToken, Boolean.TRUE, Boolean.TRUE);
        }
        // WMShell activates desktop tasks through a TO_FRONT transition. A
        // synchronous organizer transaction can update the focused root task
        // before InputDispatcher switches its focused window.
        TaskFullscreenTransitionCommand.startTransition(
                TRANSIT_TO_FRONT, transactionClass, transaction);
    }

    private static void restoreLayout(
            final int displayId,
            final String[] args) throws ReflectiveOperationException {
        final int taskCount = (args.length - 2) / 5;
        final int[] taskIds = new int[taskCount];
        final Rect[] bounds = new Rect[taskCount];
        for (int index = 0; index < taskCount; index++) {
            final int offset = 2 + index * 5;
            taskIds[index] = parseInt(args[offset], "task id");
            final int left = parseInt(args[offset + 1], "left");
            final int top = parseInt(args[offset + 2], "top");
            final int right = parseInt(args[offset + 3], "right");
            final int bottom = parseInt(args[offset + 4], "bottom");
            if (right <= left || bottom <= top) {
                throw new IllegalArgumentException("invalid bounds");
            }
            bounds[index] = new Rect(left, top, right, bottom);
        }
        applyTaskLayout(displayId, taskIds, bounds, true);
        System.out.println("task-layout-restored=" + taskCount);
    }

    private static void applyTaskLayout(
            final int displayId,
            final int[] taskIds,
            final Rect[] bounds,
            final boolean reorder) throws ReflectiveOperationException {
        applyTaskLayout(
                HiddenTaskApi.getService(),
                displayId,
                taskIds,
                bounds,
                reorder);
    }

    private static void applyTaskLayout(
            final Object service,
            final int displayId,
            final int[] taskIds,
            final Rect[] bounds,
            final boolean reorder) throws ReflectiveOperationException {
        if (taskIds == null || taskIds.length == 0
                || (bounds != null && bounds.length != taskIds.length)) {
            throw new IllegalArgumentException("invalid task layout");
        }
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        final Method setBounds = bounds == null ? null : transactionClass.getMethod(
                "setBounds", tokenClass, Rect.class);
        final Method reorderTask = reorder ? transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE) : null;
        for (int index = 0; index < taskIds.length; index++) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskIds[index]);
            if (setBounds != null) {
                setBounds.invoke(transaction, taskToken, bounds[index]);
            }
            if (reorderTask != null) {
                reorderTask.invoke(
                        transaction, taskToken, Boolean.TRUE, Boolean.TRUE);
            }
        }
        SyncWindowContainerTransaction.apply(service, transactionClass, transaction);
    }

    static void applyFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        final Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Integer.valueOf(WINDOWING_MODE_FREEFORM));
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
