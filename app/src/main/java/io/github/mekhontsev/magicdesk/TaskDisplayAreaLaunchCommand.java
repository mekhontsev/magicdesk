package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/** Launches or moves a task directly into its requested freeform state. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskDisplayAreaLaunchCommand {
    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";
    private static final String ACTIVITY_CLASS =
            "io.github.mekhontsev.magicdesk.DesktopSelfTestActivity";
    // Nubia cannot rank an empty nested TDA; use a sibling of the default TDA.
    private static final int FEATURE_ROOT = 0;
    private static final int TRANSIT_OPEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long TASK_TIMEOUT_MILLIS = 5_000L;

    private TaskDisplayAreaLaunchCommand() {
    }

    static String createTemporaryAreaAppLaunchCommand(
            final Intent intent,
            final int displayId,
            final Rect bounds) {
        return createAppLaunchCommand(intent, displayId, bounds, true);
    }

    static String createDefaultAreaAppLaunchCommand(
            final Intent intent,
            final int displayId,
            final Rect bounds) {
        return createAppLaunchCommand(intent, displayId, bounds, false);
    }

    private static String createAppLaunchCommand(
            final Intent intent,
            final int displayId,
            final Rect bounds,
            final boolean temporaryArea) {
        final ComponentName component = intent == null
                ? null : intent.getComponent();
        if (component == null || displayId < 0
                || bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException("invalid app launch");
        }
        return AppProcessCommand.run(
                TaskDisplayAreaLaunchCommand.class.getName(),
                (temporaryArea ? "app-temporary " : "app-default ")
                        + displayId
                        + " " + shellQuote(intent.toUri(
                                Intent.URI_INTENT_SCHEME))
                        + formatBounds(bounds));
    }

    static String createSelfTestLaunchCommand(
            final int displayId,
            final String token,
            final Rect bounds) {
        if (displayId < 0 || token == null
                || bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException("invalid self-test launch");
        }
        final Intent intent = new Intent()
                .setComponent(new ComponentName(PACKAGE_NAME, ACTIVITY_CLASS))
                .setData(Uri.parse("magicdesk-self-test:" + token))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(DesktopSelfTestActivity.EXTRA_DISPLAY_ID, displayId)
                .putExtra(DesktopSelfTestActivity.EXTRA_TOKEN, token)
                .putExtra(
                        DesktopSelfTestActivity.EXTRA_ALLOW_DISPLAY_MOVE,
                        true);
        return DesktopRuntimeBridge.usesTemporaryLaunchArea(displayId)
                ? createTemporaryAreaAppLaunchCommand(
                        intent, displayId, bounds)
                : createDefaultAreaAppLaunchCommand(
                        intent, displayId, bounds);
    }

    static String createMoveCommand(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) {
        if (taskId < 0 || sourceDisplayId < 0 || targetDisplayId < 0
                || bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException("invalid task move");
        }
        return AppProcessCommand.run(
                TaskDisplayAreaLaunchCommand.class.getName(),
                "move " + taskId
                        + " " + sourceDisplayId
                        + " " + targetDisplayId
                        + formatBounds(bounds));
    }

    public static void main(final String[] args) {
        final boolean temporaryApp = args.length == 7
                && "app-temporary".equals(args[0]);
        final boolean defaultApp = args.length == 7
                && "app-default".equals(args[0]);
        final boolean app = temporaryApp || defaultApp;
        final boolean move = args.length == 8 && "move".equals(args[0]);
        if (!app && !move) {
            System.err.println("usage: TaskDisplayAreaLaunchCommand "
                    + "app-temporary|app-default <display-id> <intent-uri> "
                    + "<left> <top> <right> <bottom> | "
                    + "move <task-id> <source-display-id> "
                    + "<target-display-id> <left> <top> <right> <bottom>");
            System.exit(64);
            return;
        }

        Object organizer = null;
        Object areaToken = null;
        try {
            final int taskIdArgument = move
                    ? parseNonNegative(args[1], "task id") : -1;
            final int sourceDisplayId = move
                    ? parseNonNegative(args[2], "source display id") : -1;
            final int displayId = parseNonNegative(
                    args[move ? 3 : 1], "display id");
            final Rect bounds = parseBounds(args, move ? 4 : 3);
            final Class<?> containerTokenClass = temporaryApp
                    ? Class.forName("android.window.WindowContainerToken")
                    : null;
            final Class<?> organizerClass;
            if (defaultApp || move) {
                organizerClass = null;
            } else {
                organizerClass = Class.forName(
                        "android.window.DisplayAreaOrganizer");
                final Executor directExecutor = Runnable::run;
                organizer = organizerClass.getConstructor(Executor.class)
                        .newInstance(directExecutor);
                final Object appeared = organizerClass.getMethod(
                        "createTaskDisplayArea",
                        Integer.TYPE,
                        Integer.TYPE,
                        String.class)
                        .invoke(
                                organizer,
                                Integer.valueOf(displayId),
                                Integer.valueOf(FEATURE_ROOT),
                                "MagicDesk launch");
                final Object areaInfo = appeared.getClass()
                        .getMethod("getDisplayAreaInfo")
                        .invoke(appeared);
                areaToken = HiddenTaskApi.getField(areaInfo, "token");
                closeLeash(appeared);
            }

            final Object service = HiddenTaskApi.getService();
            final int taskId;
            if (app) {
                final Intent intent = createAppIntent(args[2]);
                taskId = launchTask(
                        service,
                        displayId,
                        intent,
                        intent.getComponent().getPackageName(),
                        bounds,
                        containerTokenClass,
                        areaToken,
                        defaultApp);
                if (defaultApp) {
                    // Compatibility fallback for firmware that accepts the
                    // launch transaction but ignores its initial bounds.
                    if (!isTaskFreeformAtBounds(
                            service, displayId, taskId, bounds)) {
                        TaskWindowingCommand.applyFreeform(
                                service, displayId, taskId, bounds);
                    }
                    waitForTaskWindowingMode(
                            service,
                            displayId,
                            taskId,
                            WINDOWING_MODE_FREEFORM);
                }
            } else {
                moveExistingTask(
                        service,
                        taskIdArgument,
                        sourceDisplayId,
                        displayId,
                        bounds);
                taskId = taskIdArgument;
            }
            if (temporaryApp) {
                // A null parent means the default task display area on the
                // task's current display.
                reparentTask(
                        service,
                        HiddenTaskApi.requireTaskToken(
                                service, displayId, taskId),
                        null,
                        bounds,
                        containerTokenClass);
                waitForTask(
                        service,
                        displayId,
                        taskId,
                        null,
                        null);
                deleteArea(
                        organizerClass,
                        organizer,
                        containerTokenClass,
                        areaToken);
                areaToken = null;
                waitForTask(
                        service,
                        displayId,
                        taskId,
                        null,
                        null);
            }
            System.out.println(move
                    ? "task-freeform-move=" + taskId
                    : "task-display-area-launch=" + taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("freeform task transition failed: "
                    + usefulMessage(error));
            System.exit(1);
        } finally {
            if (organizer != null && areaToken != null) {
                try {
                    deleteArea(
                            organizer.getClass(),
                            organizer,
                            areaToken.getClass(),
                            areaToken);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Process death releases any remaining organizer-owned area.
                }
            }
        }
    }

    private static int launchTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Rect bounds,
            final Class<?> containerTokenClass,
            final Object areaToken,
            final boolean launchInTransition)
            throws ReflectiveOperationException {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        options.setLaunchBounds(bounds);
        if (areaToken != null) {
            ActivityOptions.class.getMethod(
                    "setLaunchTaskDisplayArea", containerTokenClass)
                    .invoke(options, areaToken);
        }
        ActivityOptions.class.getMethod(
                "setLaunchWindowingMode", Integer.TYPE)
                .invoke(options, Integer.valueOf(WINDOWING_MODE_FREEFORM));
        final Set<Integer> existingTaskIds = taskIdsOnDisplay(
                service, displayId);
        if (launchInTransition) {
            launchPendingIntentTransition(intent, options);
        } else {
            launchActivity(service, intent, options);
        }
        return waitForTask(
                service,
                displayId,
                -1,
                expectedPackage,
                existingTaskIds);
    }

    private static void launchPendingIntentTransition(
            final Intent intent,
            final ActivityOptions options) throws ReflectiveOperationException {
        ActivityOptions.class.getMethod(
                "setPendingIntentBackgroundActivityStartMode", Integer.TYPE)
                .invoke(options, Integer.valueOf(3));
        // Match WMShell's launch path and avoid its initial-bounds regression.
        ActivityOptions.class.getMethod(
                "setFlexibleLaunchSize", Boolean.TYPE)
                .invoke(options, Boolean.TRUE);
        final Context shellContext = createShellContext();
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                shellContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        transactionClass.getMethod(
                "sendPendingIntent",
                PendingIntent.class,
                Intent.class,
                Bundle.class)
                .invoke(transaction, pendingIntent, intent, options.toBundle());
        TaskFullscreenTransitionCommand.startTransition(
                TRANSIT_OPEN, transactionClass, transaction);
    }

    private static Context createShellContext()
            throws ReflectiveOperationException {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        final Class<?> activityThreadClass =
                Class.forName("android.app.ActivityThread");
        final Object activityThread = activityThreadClass
                .getMethod("systemMain")
                .invoke(null);
        final Context systemContext = (Context) activityThreadClass
                .getMethod("getSystemContext")
                .invoke(activityThread);
        try {
            return systemContext.createPackageContext(
                    "com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(
                    "Android shell package is unavailable", error);
        }
    }

    private static void launchActivity(
            final Object service,
            final Intent intent,
            final ActivityOptions options) throws ReflectiveOperationException {
        final Class<?> applicationThreadClass =
                Class.forName("android.app.IApplicationThread");
        final Class<?> profilerInfoClass =
                Class.forName("android.app.ProfilerInfo");
        final Method startActivity = service.getClass().getMethod(
                "startActivity",
                applicationThreadClass,
                String.class,
                String.class,
                Intent.class,
                String.class,
                IBinder.class,
                String.class,
                Integer.TYPE,
                Integer.TYPE,
                profilerInfoClass,
                Bundle.class);
        final int startResult = ((Integer) startActivity.invoke(
                service,
                null,
                "com.android.shell",
                null,
                intent,
                null,
                null,
                null,
                Integer.valueOf(-1),
                Integer.valueOf(0),
                null,
                options.toBundle())).intValue();
        if (startResult < 0) {
            throw new IllegalStateException(
                    "startActivity returned " + startResult);
        }
    }

    private static int waitForTask(
            final Object service,
            final int displayId,
            final int expectedTaskId,
            final String expectedPackage,
            final Set<Integer> excludedTaskIds)
            throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis() + TASK_TIMEOUT_MILLIS;
        do {
            final List<?> tasks = HiddenTaskApi.getTasks(service, displayId);
            for (final Object task : tasks) {
                final int taskId = HiddenTaskApi.getIntField(task, "taskId");
                final boolean matchesTask = expectedTaskId >= 0
                        ? taskId == expectedTaskId
                        : expectedPackage != null
                                && (excludedTaskIds == null
                                        || !excludedTaskIds.contains(
                                                Integer.valueOf(taskId)))
                                && expectedPackage.equals(
                                        HiddenTaskApi.getTaskPackage(task));
                if (matchesTask) {
                    return taskId;
                }
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException("task did not appear");
    }

    private static void waitForTaskWindowingMode(
            final Object service,
            final int displayId,
            final int taskId,
            final int windowingMode) throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + TASK_TIMEOUT_MILLIS;
        do {
            for (final Object task : HiddenTaskApi.getTasks(
                    service, displayId)) {
                if (HiddenTaskApi.getIntField(task, "taskId") == taskId
                        && HiddenTaskApi.getWindowConfigurationValue(
                                task, "getWindowingMode")
                                == windowingMode) {
                    return;
                }
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "task did not enter requested windowing mode");
    }

    private static void waitForTaskFreeformBounds(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect expectedBounds) throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + TASK_TIMEOUT_MILLIS;
        do {
            for (final Object task : HiddenTaskApi.getTasks(
                    service, displayId)) {
                if (HiddenTaskApi.getIntField(task, "taskId") == taskId
                        && HiddenTaskApi.getWindowConfigurationValue(
                                task, "getWindowingMode")
                                == WINDOWING_MODE_FREEFORM) {
                    final Object windowConfiguration =
                            HiddenTaskApi.getWindowConfiguration(task);
                    final Object bounds = windowConfiguration.getClass()
                            .getMethod("getBounds")
                            .invoke(windowConfiguration);
                    if (expectedBounds.equals(bounds)) {
                        return;
                    }
                }
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "task did not reach the requested freeform bounds");
    }

    private static boolean isTaskFreeformAtBounds(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect expectedBounds) throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.requireTask(
                service, displayId, taskId);
        final int windowingMode = HiddenTaskApi.getWindowConfigurationValue(
                task, "getWindowingMode");
        final Object windowConfiguration =
                HiddenTaskApi.getWindowConfiguration(task);
        final Object bounds = windowConfiguration.getClass()
                .getMethod("getBounds")
                .invoke(windowConfiguration);
        return windowingMode == WINDOWING_MODE_FREEFORM
                && expectedBounds.equals(bounds);
    }

    private static Set<Integer> taskIdsOnDisplay(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        final Set<Integer> taskIds = new HashSet<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            taskIds.add(Integer.valueOf(
                    HiddenTaskApi.getIntField(task, "taskId")));
        }
        return taskIds;
    }

    private static Intent createAppIntent(final String intentUri) {
        final Intent intent;
        try {
            intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("invalid app launch intent", error);
        }
        final ComponentName component = intent.getComponent();
        final String packageName = component == null
                ? null : component.getPackageName();
        final String className = component == null
                ? null : component.getClassName();
        if (!PackageNameValidator.isSafe(packageName)
                || !AppLaunchTarget.isSafeClassName(className)
                || className.isEmpty()) {
            throw new IllegalArgumentException("invalid app launch target");
        }
        return intent.addFlags(additionalLaunchFlags(intent.getFlags()))
                .putExtra("start_from_heartservice_app_lock", true);
    }

    static int additionalLaunchFlags(final int intentFlags) {
        final int separateDocumentFlags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        if ((intentFlags & separateDocumentFlags) == separateDocumentFlags) {
            return Intent.FLAG_ACTIVITY_NEW_TASK;
        }
        return Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
    }

    private static void reparentTask(
            final Object service,
            final Object taskToken,
            final Object parentToken,
            final Rect bounds,
            final Class<?> tokenClass) throws ReflectiveOperationException {
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass
                .getConstructor().newInstance();
        transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Integer.valueOf(WINDOWING_MODE_FREEFORM));
        transactionClass.getMethod(
                "setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, bounds);
        transactionClass.getMethod(
                "reparent", tokenClass, tokenClass, Boolean.TYPE)
                .invoke(
                        transaction,
                        new Object[]{taskToken, parentToken, Boolean.TRUE});
        SyncWindowContainerTransaction.apply(
                service, transactionClass, transaction);
    }

    private static void moveExistingTask(
            final Object service,
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws ReflectiveOperationException {
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, sourceDisplayId, taskId);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(targetDisplayId);
        options.setLaunchBounds(bounds);
        ActivityOptions.class.getMethod(
                "setLaunchWindowingMode", Integer.TYPE)
                .invoke(options, Integer.valueOf(WINDOWING_MODE_FREEFORM));
        ActivityOptions.class.getMethod(
                "setFlexibleLaunchSize", Boolean.TYPE)
                .invoke(options, Boolean.TRUE);
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Object transaction = transactionClass
                .getConstructor().newInstance();
        // Apply the display move, mode and geometry in one visible transition.
        transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Integer.valueOf(WINDOWING_MODE_FREEFORM));
        transactionClass.getMethod(
                "setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, bounds);
        transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass,
                transaction,
                tokenClass,
                taskToken,
                false);
        transactionClass.getMethod(
                "startTask", Integer.TYPE, Bundle.class)
                .invoke(
                        transaction,
                        Integer.valueOf(taskId),
                        options.toBundle());
        TaskFullscreenTransitionCommand.startTransition(
                TRANSIT_OPEN, transactionClass, transaction);
        waitForTaskFreeformBounds(
                service, targetDisplayId, taskId, bounds);
    }

    private static void deleteArea(
            final Class<?> organizerClass,
            final Object organizer,
            final Class<?> tokenClass,
            final Object token) throws ReflectiveOperationException {
        organizerClass.getMethod("deleteTaskDisplayArea", tokenClass)
                .invoke(organizer, token);
    }

    private static void closeLeash(final Object appeared) {
        try {
            final Object leash = appeared.getClass()
                    .getMethod("getLeash")
                    .invoke(appeared);
            if (leash != null) {
                leash.getClass().getMethod("release").invoke(leash);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The process owns no lasting SurfaceControl reference.
        }
    }

    private static Rect parseBounds(
            final String[] args,
            final int offset) {
        final Rect bounds = new Rect(
                Integer.parseInt(args[offset]),
                Integer.parseInt(args[offset + 1]),
                Integer.parseInt(args[offset + 2]),
                Integer.parseInt(args[offset + 3]));
        if (bounds.left < 0 || bounds.top < 0 || bounds.isEmpty()) {
            throw new IllegalArgumentException("invalid bounds");
        }
        return bounds;
    }

    private static int parseNonNegative(
            final String value,
            final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }

    private static String formatBounds(final Rect bounds) {
        return " " + bounds.left
                + " " + bounds.top
                + " " + bounds.right
                + " " + bounds.bottom;
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }
}
