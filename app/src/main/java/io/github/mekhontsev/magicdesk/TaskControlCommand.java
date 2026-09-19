package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;

import java.lang.reflect.InvocationTargetException;

public final class TaskControlCommand {
    private static final String PACKAGE_NAME =
            DesktopHostComponents.PACKAGE_NAME;
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
            if (activityType != FrameworkTaskSnapshot.ACTIVITY_TYPE_HOME) {
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
