package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes native desktop state from application tasks on the primary display.
 *
 * <p>Nubia Quickstep crashes while binding its Recents desktop group when any
 * standard task on display 0 remains freeform. Apply every normalization in one
 * transaction so HOME never observes a partially cleaned task stack.</p>
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class PhoneFreeformCleanupCommand {
    private static final int PHONE_DISPLAY_ID = 0;
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private PhoneFreeformCleanupCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 0) {
            System.err.println("usage: PhoneFreeformCleanupCommand");
            System.exit(64);
            return;
        }

        try {
            final int normalized = normalizePhoneTasks();
            System.out.println("phone-freeform-normalized=" + normalized);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("phone freeform cleanup failed: " + cause);
            System.exit(1);
        }
    }

    private static int normalizePhoneTasks()
            throws ReflectiveOperationException {
        final Object service = getActivityTaskManagerService();
        final List<Object> tasks = findFreeformApplicationTasks(service);
        if (tasks.isEmpty()) {
            return 0;
        }

        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        for (final Object task : tasks) {
            final Object taskToken =
                    task.getClass().getField("token").get(task);
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            transactionClass.getMethod(
                    "setDensityDpi", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken, Integer.valueOf(0));
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass,
                    transaction,
                    tokenClass,
                    taskToken,
                    true);
        }
        SyncWindowContainerTransaction.apply(
                service, transactionClass, transaction);
        return tasks.size();
    }

    private static List<Object> findFreeformApplicationTasks(
            final Object service) throws ReflectiveOperationException {
        final Object result = service.getClass()
                .getMethod(
                        "getTasks",
                        Integer.TYPE,
                        Boolean.TYPE,
                        Boolean.TYPE,
                        Integer.TYPE)
                .invoke(
                        service,
                        Integer.valueOf(100),
                        Boolean.FALSE,
                        Boolean.TRUE,
                        Integer.valueOf(PHONE_DISPLAY_ID));
        final List<Object> tasks = new ArrayList<>();
        if (!(result instanceof List)) {
            return tasks;
        }
        for (final Object task : (List<?>) result) {
            if (getActivityType(task) == ACTIVITY_TYPE_STANDARD
                    && getWindowingMode(task) == WINDOWING_MODE_FREEFORM) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    private static int getActivityType(final Object task)
            throws ReflectiveOperationException {
        return windowConfiguration(task, "getActivityType");
    }

    private static int getWindowingMode(final Object task)
            throws ReflectiveOperationException {
        return windowConfiguration(task, "getWindowingMode");
    }

    private static int windowConfiguration(
            final Object task,
            final String methodName) throws ReflectiveOperationException {
        final Object configuration =
                task.getClass().getField("configuration").get(task);
        final Object windowConfiguration = configuration.getClass()
                .getField("windowConfiguration").get(configuration);
        return ((Integer) windowConfiguration.getClass()
                .getMethod(methodName)
                .invoke(windowConfiguration)).intValue();
    }

    private static Object getActivityTaskManagerService()
            throws ReflectiveOperationException {
        final Class<?> activityTaskManager =
                Class.forName("android.app.ActivityTaskManager");
        final Method getService =
                activityTaskManager.getDeclaredMethod("getService");
        getService.setAccessible(true);
        return getService.invoke(null);
    }
}
