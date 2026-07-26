package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ComponentName;

import java.lang.reflect.Method;
import java.util.List;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskControlCommand {
    private static final String PACKAGE_NAME = "io.github.mekhontsev.magicdesk";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int ACTIVITY_TYPE_HOME = 2;

    private TaskControlCommand() {
    }

    public static void main(final String[] args) {
        final boolean focusStack = args.length >= 2 && "focus-stack".equals(args[0]);
        final boolean prepareDesktop =
                args.length == 2 && "prepare-desktop".equals(args[0]);
        final boolean queryVisibleApp =
                args.length == 2 && "has-visible-app".equals(args[0]);
        final boolean queryDesktopHome =
                args.length == 2 && "has-desktop-home".equals(args[0]);
        final boolean singleTaskAction = args.length == 2
                && ("focus".equals(args[0]) || "remove".equals(args[0]));
        if (!focusStack && !prepareDesktop && !queryVisibleApp && !queryDesktopHome
                && !singleTaskAction) {
            System.err.println("usage: TaskControlCommand "
                    + "<focus|remove> <task-id> | focus-stack <task-id>... "
                    + "| prepare-desktop <display-id>"
                    + "| has-visible-app <display-id>"
                    + "| has-desktop-home <display-id>");
            System.exit(64);
            return;
        }

        final int[] taskIds = new int[args.length - 1];
        try {
            for (int index = 1; index < args.length; index++) {
                taskIds[index - 1] = Integer.parseInt(args[index]);
                if (taskIds[index - 1] < 0) {
                    throw new NumberFormatException("negative task id");
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("invalid task id");
            System.exit(64);
            return;
        }

        try {
            final Class<?> activityTaskManager = Class.forName("android.app.ActivityTaskManager");
            final Method getService = activityTaskManager.getDeclaredMethod("getService");
            getService.setAccessible(true);
            final Object service = getService.invoke(null);
            if (queryDesktopHome) {
                System.out.println("desktop-home-task="
                        + hasDesktopHomeTask(service, taskIds[0]));
            } else if (queryVisibleApp) {
                System.out.println("visible-app-task="
                        + hasVisibleAppTask(service, taskIds[0]));
            } else if (prepareDesktop) {
                prepareDesktopTask(service, taskIds[0]);
            } else if (focusStack) {
                for (int index = 0; index < taskIds.length; index++) {
                    try {
                        moveTaskToFront(service, taskIds[index]);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        if (index == taskIds.length - 1) {
                            throw e;
                        }
                        System.err.println("skipped stale task=" + taskIds[index]);
                    }
                }
                System.out.println("task-stack-focused=" + taskIds.length);
            } else if ("focus".equals(args[0])) {
                moveTaskToFront(service, taskIds[0]);
                System.out.println("task-focused=" + taskIds[0]);
            } else {
                final boolean removed = removeTask(service, taskIds[0]);
                System.out.println("task-removed=" + taskIds[0] + " result=" + removed);
                if (!removed) {
                    System.exit(1);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("task control failed: " + e);
            System.exit(1);
        }
    }

    static void focusTask(final int taskId) throws ReflectiveOperationException {
        final Class<?> activityTaskManager = Class.forName("android.app.ActivityTaskManager");
        final Method getService = activityTaskManager.getDeclaredMethod("getService");
        getService.setAccessible(true);
        moveTaskToFront(getService.invoke(null), taskId);
    }

    static void moveTaskToFront(final Object service, final int taskId)
            throws ReflectiveOperationException {
        Method target = null;
        for (final Method method : service.getClass().getMethods()) {
            if ("moveTaskToFront".equals(method.getName())) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException("moveTaskToFront");
        }

        final Class<?>[] parameterTypes = target.getParameterTypes();
        final Object[] arguments = new Object[parameterTypes.length];
        boolean taskIdAssigned = false;
        boolean packageAssigned = false;
        for (int i = 0; i < parameterTypes.length; i++) {
            final Class<?> type = parameterTypes[i];
            if (type == Integer.TYPE) {
                arguments[i] = Integer.valueOf(taskIdAssigned ? 0 : taskId);
                taskIdAssigned = true;
            } else if (type == String.class && !packageAssigned) {
                arguments[i] = "io.github.mekhontsev.magicdesk";
                packageAssigned = true;
            } else if (type == Boolean.TYPE) {
                arguments[i] = Boolean.FALSE;
            } else {
                arguments[i] = null;
            }
        }
        if (!taskIdAssigned) {
            throw new NoSuchMethodException("moveTaskToFront task id");
        }
        target.invoke(service, arguments);
    }

    private static boolean hasVisibleAppTask(final Object service, final int displayId)
            throws ReflectiveOperationException {
        final Object result = service.getClass()
                .getMethod("getTasks", Integer.TYPE, Boolean.TYPE, Boolean.TYPE, Integer.TYPE)
                .invoke(service, Integer.valueOf(100), Boolean.FALSE, Boolean.TRUE,
                        Integer.valueOf(displayId));
        if (!(result instanceof List)) {
            throw new IllegalStateException("getTasks returned no task list");
        }
        for (final Object task : (List<?>) result) {
            if (!task.getClass().getField("isVisible").getBoolean(task)
                    || isMagicDeskTask(task)) {
                continue;
            }
            final Object configuration =
                    task.getClass().getField("configuration").get(task);
            final Object windowConfiguration = configuration.getClass()
                    .getField("windowConfiguration").get(configuration);
            final int activityType = ((Integer) windowConfiguration.getClass()
                    .getMethod("getActivityType").invoke(windowConfiguration)).intValue();
            if (activityType != ACTIVITY_TYPE_HOME) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDesktopHomeTask(final Object service, final int displayId)
            throws ReflectiveOperationException {
        final Object result = service.getClass()
                .getMethod("getTasks", Integer.TYPE, Boolean.TYPE, Boolean.TYPE, Integer.TYPE)
                .invoke(service, Integer.valueOf(100), Boolean.FALSE, Boolean.TRUE,
                        Integer.valueOf(displayId));
        if (!(result instanceof List)) {
            throw new IllegalStateException("getTasks returned no task list");
        }
        for (final Object task : (List<?>) result) {
            if (!isMagicDeskTask(task)) {
                continue;
            }
            final Object configuration =
                    task.getClass().getField("configuration").get(task);
            final Object windowConfiguration = configuration.getClass()
                    .getField("windowConfiguration").get(configuration);
            final int windowingMode = ((Integer) windowConfiguration.getClass()
                    .getMethod("getWindowingMode").invoke(windowConfiguration)).intValue();
            final int activityType = ((Integer) windowConfiguration.getClass()
                    .getMethod("getActivityType").invoke(windowConfiguration)).intValue();
            if (windowingMode == WINDOWING_MODE_FULLSCREEN
                    && activityType == ACTIVITY_TYPE_HOME) {
                return true;
            }
        }
        return false;
    }

    private static void prepareDesktopTask(final Object service, final int displayId)
            throws ReflectiveOperationException {
        final Object result = service.getClass()
                .getMethod("getTasks", Integer.TYPE, Boolean.TYPE, Boolean.TYPE, Integer.TYPE)
                .invoke(service, Integer.valueOf(100), Boolean.FALSE, Boolean.TRUE,
                        Integer.valueOf(-1));
        if (!(result instanceof List)) {
            throw new IllegalStateException("getTasks returned no task list");
        }

        int readyTaskId = -1;
        for (final Object task : (List<?>) result) {
            if (!isMagicDeskTask(task)) {
                continue;
            }
            final int taskId = task.getClass().getField("taskId").getInt(task);
            final int taskDisplayId = task.getClass().getField("displayId").getInt(task);
            final Object configuration =
                    task.getClass().getField("configuration").get(task);
            final Object windowConfiguration = configuration.getClass()
                    .getField("windowConfiguration").get(configuration);
            final int windowingMode = ((Integer) windowConfiguration.getClass()
                    .getMethod("getWindowingMode").invoke(windowConfiguration)).intValue();
            final int activityType = ((Integer) windowConfiguration.getClass()
                    .getMethod("getActivityType").invoke(windowConfiguration)).intValue();
            if (readyTaskId < 0
                    && taskDisplayId == displayId
                    && windowingMode == WINDOWING_MODE_FULLSCREEN
                    && activityType == ACTIVITY_TYPE_HOME) {
                readyTaskId = taskId;
                continue;
            }
            removeTask(service, taskId);
            System.out.println("desktop-task-removed=" + taskId
                    + " display=" + taskDisplayId
                    + " mode=" + windowingMode + " type=" + activityType);
        }

        if (readyTaskId >= 0) {
            moveTaskToFront(service, readyTaskId);
            System.out.println("desktop-task-ready=" + readyTaskId);
        } else {
            System.out.println("desktop-task-ready=none");
        }
    }

    private static boolean isMagicDeskTask(final Object task)
            throws ReflectiveOperationException {
        final ComponentName topActivity =
                (ComponentName) task.getClass().getField("topActivity").get(task);
        if (topActivity != null && PACKAGE_NAME.equals(topActivity.getPackageName())) {
            return true;
        }
        final ComponentName baseActivity =
                (ComponentName) task.getClass().getField("baseActivity").get(task);
        return baseActivity != null && PACKAGE_NAME.equals(baseActivity.getPackageName());
    }

    static boolean removeTask(final Object service, final int taskId)
            throws ReflectiveOperationException {
        for (final Method method : service.getClass().getMethods()) {
            final Class<?>[] parameterTypes = method.getParameterTypes();
            if ("removeTask".equals(method.getName())
                    && parameterTypes.length == 1
                    && parameterTypes[0] == Integer.TYPE) {
                final Object result = method.invoke(service, Integer.valueOf(taskId));
                return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
            }
        }
        throw new NoSuchMethodException("removeTask(int)");
    }
}
