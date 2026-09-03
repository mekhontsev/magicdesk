package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.os.Process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskControlCommand {
    private static final String PACKAGE_NAME =
            DesktopHostComponents.PACKAGE_NAME;
    private static final String SHELL_PACKAGE_NAME = "com.android.shell";
    private static final int SHELL_UID = 2000;
    private static final int ACTIVITY_TYPE_HOME = 2;
    private TaskControlCommand() {
    }

    public static void main(final String[] args) {
        final boolean queryVisibleApp =
                args.length == 2 && "has-visible-app".equals(args[0]);
        final boolean queryDesktopTaskId =
                args.length == 2 && "desktop-task-id".equals(args[0]);
        final boolean singleTaskAction = args.length == 2
                && "remove".equals(args[0]);
        if (!queryVisibleApp && !queryDesktopTaskId
                && !singleTaskAction) {
            System.err.println("usage: TaskControlCommand "
                    + "remove <task-id> | has-visible-app <display-id>"
                    + "| desktop-task-id <display-id>");
            System.exit(64);
            return;
        }

        final int taskId;
        try {
            taskId = Integer.parseInt(args[1]);
            if (taskId < 0) {
                throw new NumberFormatException("negative task id");
            }
        } catch (NumberFormatException e) {
            System.err.println("invalid task id");
            System.exit(64);
            return;
        }

        try {
            final Object service = HiddenTaskApi.getService();
            if (queryDesktopTaskId) {
                System.out.println("desktop-task-id="
                        + findDesktopTaskId(service, taskId));
            } else if (queryVisibleApp) {
                System.out.println("visible-app-task="
                        + hasVisibleAppTask(service, taskId));
            } else {
                final boolean removed = removeTask(service, taskId);
                System.out.println("task-removed=" + taskId + " result=" + removed);
                if (!removed) {
                    System.exit(1);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("task control failed: " + usefulFailure(e));
            System.exit(1);
        }
    }

    static void moveTaskToFront(final Object service, final int taskId)
            throws ReflectiveOperationException {
        final Method target = findMoveTaskToFrontMethod(service.getClass());

        final Class<?>[] parameterTypes = target.getParameterTypes();
        final Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            final Class<?> type = parameterTypes[i];
            if (i == 1) {
                arguments[i] = callingPackageForUid(Process.myUid());
            } else if (i == 2) {
                arguments[i] = Integer.valueOf(taskId);
            } else if (type == Integer.TYPE) {
                arguments[i] = Integer.valueOf(0);
            } else if (type == Boolean.TYPE) {
                arguments[i] = Boolean.FALSE;
            } else {
                arguments[i] = null;
            }
        }
        target.invoke(service, arguments);
    }

    static Method findMoveTaskToFrontMethod(final Class<?> serviceClass)
            throws NoSuchMethodException {
        Method fourArgument = null;
        Method fiveArgument = null;
        for (final Method method : serviceClass.getMethods()) {
            if (!"moveTaskToFront".equals(method.getName())) {
                continue;
            }
            final Class<?>[] types = method.getParameterTypes();
            if ((types.length != 4 && types.length != 5)
                    || types[0].isPrimitive()
                    || types[1] != String.class
                    || types[2] != Integer.TYPE
                    || types[3] != Integer.TYPE
                    || (types.length == 5 && types[4].isPrimitive())) {
                continue;
            }
            if (types.length == 5) {
                fiveArgument = method;
            } else {
                fourArgument = method;
            }
        }
        if (fiveArgument != null) {
            return fiveArgument;
        }
        if (fourArgument != null) {
            return fourArgument;
        }
        throw new NoSuchMethodException(
                "supported moveTaskToFront signature on "
                        + serviceClass.getName());
    }

    static String callingPackageForUid(final int uid) {
        return uid == SHELL_UID ? SHELL_PACKAGE_NAME : PACKAGE_NAME;
    }

    private static Throwable usefulFailure(final Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getCause() != null) {
            current = ((InvocationTargetException) current).getCause();
        }
        return current;
    }

    private static boolean hasVisibleAppTask(final Object service, final int displayId)
            throws ReflectiveOperationException {
        for (final Object task :
                HiddenTaskApi.getTasks(service, displayId)) {
            if (!HiddenTaskApi.isTaskVisible(task)
                    || isMagicDeskTask(task)) {
                continue;
            }
            final int activityType =
                    HiddenTaskApi.getTaskActivityType(task);
            if (activityType != ACTIVITY_TYPE_HOME) {
                return true;
            }
        }
        return false;
    }

    private static int findDesktopTaskId(final Object service, final int displayId)
            throws ReflectiveOperationException {
        for (final Object task :
                HiddenTaskApi.getTasks(service, displayId)) {
            if (!isDesktopTask(task)) {
                continue;
            }
            return HiddenTaskApi.getTaskId(task);
        }
        return -1;
    }

    private static boolean isDesktopTask(final Object task)
            throws ReflectiveOperationException {
        return isDesktopComponent(HiddenTaskApi.getTaskTopActivity(task))
                || isDesktopComponent(
                        HiddenTaskApi.getTaskBaseActivity(task));
    }

    private static boolean isDesktopComponent(final ComponentName component) {
        return DesktopHostComponents.isHostComponent(component);
    }

    private static boolean isMagicDeskTask(final Object task)
            throws ReflectiveOperationException {
        final ComponentName topActivity =
                HiddenTaskApi.getTaskTopActivity(task);
        if (topActivity != null && PACKAGE_NAME.equals(topActivity.getPackageName())) {
            return true;
        }
        final ComponentName baseActivity =
                HiddenTaskApi.getTaskBaseActivity(task);
        return baseActivity != null && PACKAGE_NAME.equals(baseActivity.getPackageName());
    }

    public static boolean removeTask(final Object service, final int taskId)
            throws ReflectiveOperationException {
        return HiddenTaskApi.removeTask(service, taskId);
    }
}
