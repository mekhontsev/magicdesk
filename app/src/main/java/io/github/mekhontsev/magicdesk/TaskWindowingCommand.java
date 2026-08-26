package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

import java.lang.reflect.Method;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskWindowingCommand {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
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
            if (args.length == 7 && "rebuild-freeform".equals(args[0])) {
                rebuildFreeform(parseInt(args[1], "display id"),
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
            if (args.length == 4 && "desktop-host".equals(args[0])) {
                setDesktopHost(parseInt(args[1], "display id"),
                        parseInt(args[2], "task id"),
                        parseFlag(args[3], "refresh caption"));
                return;
            }
            if (args.length == 4 && "minimize".equals(args[0])) {
                minimize(parseInt(args[1], "display id"),
                        parseInt(args[2], "task id"),
                        parseInt(args[3], "focus task id"));
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
                    + "<freeform|rebuild-freeform|bounds display task left top right bottom"
                    + "|desktop-host display task refresh-caption"
                    + "|minimize display task focus-task"
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
        ShellPreparedTaskTransition.applyFreeform(
                HiddenTaskApi.getService(),
                displayId,
                taskId,
                new Rect(left, top, right, bottom));
        System.out.println("task-freeform=" + taskId);
    }

    private static void rebuildFreeform(final int displayId, final int taskId,
            final int left, final int top, final int right, final int bottom)
            throws ReflectiveOperationException {
        if (right <= left || bottom <= top) {
            throw new IllegalArgumentException("invalid bounds");
        }
        final Object service = HiddenTaskApi.getService();
        final Rect bounds = new Rect(left, top, right, bottom);
        boolean hidden = false;
        try {
            // A client configuration change can return a task to nominal
            // freeform without asking WMShell to rebuild its caption leash.
            // Hide its current surface first, establish a real mode boundary,
            // then let the normal freeform transition recreate the native
            // decoration without exposing an intermediate fullscreen frame.
            hidden = true;
            ShellPreparedTaskTransition.prepareFreeform(
                    service, displayId, taskId, bounds);
            TaskDisplayAreaLaunchCommand.waitForTaskVisibility(
                    service, displayId, taskId, false);
            ShellPreparedTaskTransition.prepareFullscreen(
                    service, displayId, taskId);
            TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                    service, displayId, taskId, WINDOWING_MODE_FULLSCREEN);
            ShellPreparedTaskTransition.showPreparedFreeform(
                    service, displayId, taskId, bounds);
            TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                    service, displayId, taskId, bounds);
            hidden = false;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (hidden) {
                try {
                    ShellPreparedTaskTransition.restorePreparedTask(
                            service,
                            displayId,
                            taskId,
                            WINDOWING_MODE_FREEFORM,
                            bounds);
                } catch (ReflectiveOperationException
                        | RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            throw error;
        }
        System.out.println("task-freeform-rebuilt=" + taskId);
    }

    private static void setDesktopHost(
            final int displayId,
            final int taskId,
            final boolean refreshCaption)
            throws ReflectiveOperationException {
        TaskFullscreenTransitionCommand.applyFullscreen(
                displayId,
                taskId,
                true,
                refreshCaption);
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
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, transaction);
        System.out.println("task-minimized=" + taskId
                + " focused=" + focusTaskId);
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
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        focusTasks(service, displayId, taskIds, transactionClass, transaction);
    }

    static void focusTasks(
            final Object service,
            final int displayId,
            final int[] taskIds,
            final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        focusTasks(
                service,
                displayId,
                taskIds,
                transactionClass,
                transaction,
                true);
    }

    static void focusTasksWithinCurrentParent(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        focusTasksWithinCurrentParent(
                service, displayId, taskIds, transactionClass, transaction);
    }

    static void focusTasksWithinCurrentParent(
            final Object service,
            final int displayId,
            final int[] taskIds,
            final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        focusTasks(
                service,
                displayId,
                taskIds,
                transactionClass,
                transaction,
                false);
    }

    private static void focusTasks(
            final Object service,
            final int displayId,
            final int[] taskIds,
            final Class<?> transactionClass,
            final Object transaction,
            final boolean includeParents) throws ReflectiveOperationException {
        addFocusOperations(
                service,
                displayId,
                taskIds,
                transactionClass,
                transaction,
                includeParents);
        // Keep any hierarchy changes supplied by the caller and the focus
        // reorder in one transition. A synchronous hierarchy transaction
        // followed by TO_FRONT can overlap an existing WMShell transition.
        ShellWindowTransitionExecutor.playSystemTransition(
                displayId,
                ShellWindowTransitionExecutor.SystemTransition.TO_FRONT,
                transactionClass,
                transaction,
                "focus-tasks");
    }

    private static void addFocusOperations(
            final Object service,
            final int displayId,
            final int[] taskIds,
            final Class<?> transactionClass,
            final Object transaction,
            final boolean includeParents) throws ReflectiveOperationException {
        if (taskIds == null || taskIds.length == 0) {
            throw new IllegalArgumentException("missing tasks to focus");
        }
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Method reorderTask = transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE);
        for (final int taskId : taskIds) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            reorderTask.invoke(
                    transaction,
                    taskToken,
                    Boolean.TRUE,
                    Boolean.valueOf(includeParents));
        }
    }

    static void closeDesktopTask(
            final Object service,
            final int displayId,
            final int taskId,
            final int focusTaskId) throws ReflectiveOperationException {
        closeDesktopTasks(
                service,
                displayId,
                new int[] {taskId},
                focusTaskId,
                true);
    }

    static void closeFullscreenAreaTask(
            final Object service,
            final int displayId,
            final int taskId,
            final int survivorTaskId)
            throws ReflectiveOperationException {
        if (taskId == survivorTaskId) {
            throw new IllegalArgumentException(
                    "closed and surviving task match");
        }
        final Class<?> tokenClass = Class.forName(
                "android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object survivorToken = HiddenTaskApi.requireTaskToken(
                service, displayId, survivorTaskId);

        // Move focus while both tasks still share the valid fullscreen area.
        // The sync callback replaces the old visibility polling and confirms
        // that the handoff reached WindowManager before the close begins.
        final Object focusTransaction =
                transactionClass.getConstructor().newInstance();
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(
                        focusTransaction,
                        survivorToken,
                        Boolean.TRUE,
                        Boolean.TRUE);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, focusTransaction);

        final Object transaction =
                transactionClass.getConstructor().newInstance();
        final Object closingToken = HiddenTaskApi.requireTaskToken(
                service, displayId, taskId);
        transactionClass.getMethod("removeTask", tokenClass)
                .invoke(transaction, closingToken);
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(
                        transaction,
                        survivorToken,
                        Boolean.TRUE,
                        Boolean.TRUE);
        // Keep the survivor in the same fullscreen parent. The removed task is
        // already in the background, so it cannot replace survivor input focus.
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, transaction);
    }

    static void closeDesktopTasks(
            final Object service,
            final int displayId,
            final int[] taskIds,
            final int focusTaskId,
            final boolean reorderParents) throws ReflectiveOperationException {
        if (taskIds == null || taskIds.length == 0) {
            throw new IllegalArgumentException("missing tasks to close");
        }
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        final Object focusTaskToken = HiddenTaskApi.requireTaskToken(
                service, displayId, focusTaskId);
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(
                        transaction,
                        focusTaskToken,
                        Boolean.TRUE,
                        Boolean.valueOf(reorderParents));
        for (final int taskId : taskIds) {
            if (taskId == focusTaskId) {
                throw new IllegalArgumentException(
                        "closed and focused task match");
            }
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            transactionClass.getMethod("removeTask", tokenClass)
                    .invoke(transaction, taskToken);
        }
        ShellWindowTransitionExecutor.playSystemTransition(
                displayId,
                ShellWindowTransitionExecutor.SystemTransition.TO_FRONT,
                transactionClass,
                transaction,
                "close-desktop-tasks");
    }

    static void focusFullscreenTask(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        final Object task = HiddenTaskApi.requireTask(
                service, displayId, taskId);
        final Object taskToken = HiddenTaskApi.getField(task, "token");
        final int currentMode = HiddenTaskApi.getWindowConfigurationValue(
                task, "getWindowingMode");
        if (currentMode != WINDOWING_MODE_FULLSCREEN) {
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
        }
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.TRUE, Boolean.TRUE);
        ShellWindowTransitionExecutor.playSystemTransition(
                displayId,
                ShellWindowTransitionExecutor.SystemTransition.TO_FRONT,
                transactionClass,
                transaction,
                "focus-fullscreen-task");
    }

    static boolean normalizeFullscreenTask(
            final Object service,
            final int displayId,
            final Object task,
            final boolean refreshCaption) throws ReflectiveOperationException {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        final int taskId = HiddenTaskApi.getIntField(task, "taskId");
        final int originalMode = HiddenTaskApi.getWindowConfigurationValue(
                task, "getWindowingMode");
        if (originalMode == WINDOWING_MODE_FULLSCREEN) {
            return false;
        }
        final Object originalConfiguration =
                HiddenTaskApi.getWindowConfiguration(task);
        final Rect originalBounds = new Rect(
                (Rect) originalConfiguration.getClass()
                        .getMethod("getBounds")
                        .invoke(originalConfiguration));
        final int captionSourceId = refreshCaption
                ? TaskCaptionInsetsRefresher.captureCaptionSourceId(taskId)
                : TaskLocalInsetsSourceParser.NO_SOURCE_ID;
        boolean hidden = false;
        try {
            // Hide without consuming the mode boundary. The reveal is then a
            // real freeform-to-fullscreen WMShell transition, which replaces
            // the old surface and caption instead of updating only task state.
            hidden = true;
            ShellPreparedTaskTransition.hideCurrentTask(
                    service, displayId, taskId);
            ShellPreparedTaskTransition.showPreparedFullscreen(
                    service, displayId, taskId);
            TaskFullscreenTransitionCommand.awaitFullscreen(
                    service, displayId, taskId);
            hidden = false;
            TaskFullscreenTransitionCommand.refreshCaptionIfRequested(
                    service,
                    displayId,
                    taskId,
                    refreshCaption,
                    captionSourceId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (hidden) {
                try {
                    ShellPreparedTaskTransition.restorePreparedTask(
                            service,
                            displayId,
                            taskId,
                            originalMode,
                            originalBounds);
                } catch (ReflectiveOperationException
                        | RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            throw error;
        }
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
        applyFreeformLayout(displayId, taskIds, bounds, true);
        System.out.println("task-layout-restored=" + taskCount);
    }

    private static void applyFreeformLayout(
            final int displayId,
            final int[] taskIds,
            final Rect[] bounds,
            final boolean reorder) throws ReflectiveOperationException {
        applyFreeformLayout(
                HiddenTaskApi.getService(),
                displayId,
                taskIds,
                bounds,
                reorder);
    }

    private static void applyFreeformLayout(
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
        final Method setWindowingMode = transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE);
        final Method setBounds = bounds == null ? null : transactionClass.getMethod(
                "setBounds", tokenClass, Rect.class);
        final Method setForceTranslucent = transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE);
        final Method reorderTask = reorder ? transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE) : null;
        for (int index = 0; index < taskIds.length; index++) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskIds[index]);
            setWindowingMode.invoke(
                    transaction,
                    taskToken,
                    Integer.valueOf(WINDOWING_MODE_FREEFORM));
            if (setBounds != null) {
                setBounds.invoke(transaction, taskToken, bounds[index]);
            }
            setForceTranslucent.invoke(
                    transaction, taskToken, Boolean.FALSE);
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass,
                    transaction,
                    tokenClass,
                    taskToken,
                    false);
            if (reorderTask != null) {
                reorderTask.invoke(
                        transaction, taskToken, Boolean.TRUE, Boolean.TRUE);
            }
        }
        // Finalize all restored windows in one transition. Independent task
        // moves can otherwise settle out of order and overwrite each other's
        // freeform state on physical projection displays.
        ShellWindowTransitionExecutor.playSystemTransition(
                displayId,
                ShellWindowTransitionExecutor.SystemTransition.CHANGE,
                transactionClass,
                transaction,
                "restore-desktop-tasks");
        if (bounds != null) {
            for (int index = 0; index < taskIds.length; index++) {
                TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                        service, displayId, taskIds[index], bounds[index]);
            }
        }
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
