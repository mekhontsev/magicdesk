package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Intent;
import android.view.Display;

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

    static List<?> getAllTasks(final Object service)
            throws ReflectiveOperationException {
        return getTasks(service, Display.INVALID_DISPLAY);
    }

    static List<?> getRecentTasks(final Object service)
            throws ReflectiveOperationException {
        final Object slice = service.getClass()
                .getMethod(
                        "getRecentTasks",
                        Integer.TYPE,
                        Integer.TYPE,
                        Integer.TYPE)
                .invoke(service, Integer.valueOf(MAX_TASKS),
                        Integer.valueOf(0), Integer.valueOf(0));
        if (slice == null) {
            throw new IllegalStateException("getRecentTasks returned no result");
        }
        final Object result = slice.getClass().getMethod("getList").invoke(slice);
        if (!(result instanceof List)) {
            throw new IllegalStateException("getRecentTasks returned no task list");
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

    static String getTaskPackage(final Object task) {
        final ComponentName component = getTaskComponent(task);
        return component == null ? null : component.getPackageName();
    }

    static ComponentName getTaskTopComponent(final Object task) {
        final Object topActivity = getOptionalField(task, "topActivity");
        return topActivity instanceof ComponentName
                ? (ComponentName) topActivity : getTaskComponent(task);
    }

    static ComponentName getTaskComponent(final Object task) {
        final String[] componentFields = {
                "baseActivity", "realActivity", "origActivity", "topActivity"
        };
        for (final String field : componentFields) {
            final Object value = getOptionalField(task, field);
            if (value instanceof ComponentName) {
                return (ComponentName) value;
            }
        }
        final Object baseIntent = getOptionalField(task, "baseIntent");
        if (baseIntent instanceof Intent) {
            final ComponentName component = ((Intent) baseIntent).getComponent();
            if (component != null) {
                return component;
            }
        }
        return null;
    }

    static int getTaskDisplayId(final Object task) {
        final Object value = getOptionalField(task, "displayId");
        return value instanceof Number ? ((Number) value).intValue() : -1;
    }

    static void registerTaskStackListener(
            final Object service,
            final TaskStackListener listener)
            throws ReflectiveOperationException {
        final Class<?> listenerClass =
                Class.forName("android.app.ITaskStackListener");
        service.getClass().getMethod(
                "registerTaskStackListener", listenerClass)
                .invoke(service, listener);
    }

    static void unregisterTaskStackListener(
            final Object service,
            final TaskStackListener listener)
            throws ReflectiveOperationException {
        final Class<?> listenerClass =
                Class.forName("android.app.ITaskStackListener");
        service.getClass().getMethod(
                "unregisterTaskStackListener", listenerClass)
                .invoke(service, listener);
    }

    private static Object getOptionalField(final Object target, final String name) {
        try {
            return getField(target, name);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
