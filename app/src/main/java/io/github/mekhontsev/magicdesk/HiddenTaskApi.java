package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

@SuppressLint("BlockedPrivateApi")
final class HiddenTaskApi {
    private static final int MAX_TASKS = 100;

    private HiddenTaskApi() {
    }

    static Object getService() throws ReflectiveOperationException {
        final Class<?> activityTaskManager =
                Class.forName("android.app.ActivityTaskManager");
        final Method getService =
                activityTaskManager.getDeclaredMethod("getService");
        getService.setAccessible(true);
        return getService.invoke(null);
    }

    static List<?> getTasks(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        return getTasks(service, displayId, MAX_TASKS);
    }

    static List<?> getTasks(
            final Object service,
            final int displayId,
            final int maxTasks) throws ReflectiveOperationException {
        final Object result = service.getClass()
                .getMethod(
                        "getTasks",
                        Integer.TYPE,
                        Boolean.TYPE,
                        Boolean.TYPE,
                        Integer.TYPE)
                .invoke(
                        service,
                        Integer.valueOf(maxTasks),
                        Boolean.FALSE,
                        Boolean.TRUE,
                        Integer.valueOf(displayId));
        if (!(result instanceof List)) {
            throw new IllegalStateException("getTasks returned no task list");
        }
        return (List<?>) result;
    }

    static Object findTask(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        for (final Object task : getTasks(service, displayId)) {
            if (getIntField(task, "taskId") == taskId) {
                return task;
            }
        }
        return null;
    }

    static Object requireTask(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final Object task = findTask(service, displayId, taskId);
        if (task == null) {
            throw new IllegalStateException(
                    "task " + taskId
                            + " not found on display " + displayId);
        }
        return task;
    }

    static Object requireTaskToken(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        return getField(
                requireTask(service, displayId, taskId), "token");
    }

    static Object getField(
            final Object target,
            final String name) throws ReflectiveOperationException {
        return target.getClass().getField(name).get(target);
    }

    static int getIntField(
            final Object target,
            final String name) throws ReflectiveOperationException {
        final Field field = target.getClass().getField(name);
        return field.getInt(target);
    }

    static boolean getBooleanField(
            final Object target,
            final String name) throws ReflectiveOperationException {
        final Field field = target.getClass().getField(name);
        return field.getBoolean(target);
    }

    static Object getWindowConfiguration(
            final Object task) throws ReflectiveOperationException {
        final Object configuration = getField(task, "configuration");
        return getField(configuration, "windowConfiguration");
    }

    static int getWindowConfigurationValue(
            final Object task,
            final String methodName) throws ReflectiveOperationException {
        final Object windowConfiguration = getWindowConfiguration(task);
        return ((Integer) windowConfiguration.getClass()
                .getMethod(methodName)
                .invoke(windowConfiguration)).intValue();
    }
}
