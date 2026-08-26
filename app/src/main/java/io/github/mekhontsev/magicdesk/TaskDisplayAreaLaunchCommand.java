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
import android.view.Display;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Launches or moves a task directly into its requested freeform state. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskDisplayAreaLaunchCommand {
    interface TransitionStartedCallback {
        void onTransitionStarted(
                ShellWindowTransitionExecutor.OpeningTransition transition)
                throws ReflectiveOperationException;
    }

    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";
    private static final String ACTIVITY_CLASS =
            "io.github.mekhontsev.magicdesk.DesktopSelfTestActivity";
    private static final String BROWSER_ACTIVITY_CLASS =
            "io.github.mekhontsev.magicdesk.DesktopSelfTestBrowserActivity";
    private static final int TRANSIT_OPEN = 1;
    private static final int ACTIVITY_TYPE_UNDEFINED = 0;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long TASK_TIMEOUT_MILLIS = 5_000L;
    private static final int FAILURE_MESSAGE_LIMIT = 500;

    private TaskDisplayAreaLaunchCommand() {
    }

    static Intent createSelfTestIntent(
            final int displayId,
            final String token,
            final boolean browser,
            final DesktopSelfTestFixtureAppearance appearance) {
        if (displayId < 0 || token == null || appearance == null) {
            throw new IllegalArgumentException("invalid self-test launch");
        }
        final Intent intent = new Intent()
                .setComponent(new ComponentName(
                        PACKAGE_NAME,
                        browser ? BROWSER_ACTIVITY_CLASS : ACTIVITY_CLASS))
                .setData(Uri.parse("magicdesk-self-test:" + token))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(DesktopSelfTestActivity.EXTRA_DISPLAY_ID, displayId)
                .putExtra(DesktopSelfTestActivity.EXTRA_TOKEN, token)
                .putExtra(
                        DesktopSelfTestActivity.EXTRA_ALLOW_DISPLAY_MOVE,
                        true);
        appearance.putInto(intent);
        return intent;
    }

    static String createMoveCommand(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) {
        return createMoveCommand(
                taskId, sourceDisplayId, targetDisplayId, bounds, null);
    }

    static String createObservedMoveCommand(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds,
            final DesktopTransitionSurfaceProbe.Reference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("surface reference is required");
        }
        return createMoveCommand(
                taskId,
                sourceDisplayId,
                targetDisplayId,
                bounds,
                reference);
    }

    private static String createMoveCommand(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds,
            final DesktopTransitionSurfaceProbe.Reference reference) {
        if (taskId < 0 || sourceDisplayId < 0 || targetDisplayId < 0
                || !hasExplicitBounds(bounds)) {
            throw new IllegalArgumentException("invalid task move");
        }
        return AppProcessCommand.run(
                TaskDisplayAreaLaunchCommand.class.getName(),
                (reference == null ? "move " : "move-observed ") + taskId
                        + " " + sourceDisplayId
                        + " " + targetDisplayId
                        + formatBounds(bounds)
                        + (reference == null
                                ? "" : " " + reference.commandArguments()));
    }

    static String createRootTaskMoveCommand(
            final int taskId,
            final int rootTaskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) {
        return createRootTaskMoveCommand(
                taskId,
                rootTaskId,
                sourceDisplayId,
                targetDisplayId,
                bounds,
                null);
    }

    static String createRootTaskMoveCommand(
            final int taskId,
            final int rootTaskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds,
            final DesktopTransitionSurfaceProbe.Reference reference) {
        if (taskId < 0 || rootTaskId < 0 || sourceDisplayId < 0
                || targetDisplayId < 0 || !hasExplicitBounds(bounds)) {
            throw new IllegalArgumentException("invalid root task move");
        }
        return AppProcessCommand.run(
                TaskDisplayAreaLaunchCommand.class.getName(),
                (reference == null
                        ? "move-root " : "move-root-observed ")
                        + taskId
                        + " " + rootTaskId
                        + " " + sourceDisplayId
                        + " " + targetDisplayId
                        + formatBounds(bounds)
                        + (reference == null
                                ? "" : " " + reference.commandArguments()));
    }

    public static void main(final String[] args) {
        final boolean app = args.length == 7 && "app".equals(args[0]);
        final boolean move = args.length == 8 && "move".equals(args[0]);
        final boolean observedMove = args.length == 12
                && "move-observed".equals(args[0]);
        final boolean taskMove = move || observedMove;
        final boolean plainRootMove = args.length == 9
                && "move-root".equals(args[0]);
        final boolean observedRootMove = args.length == 13
                && "move-root-observed".equals(args[0]);
        final boolean rootMove = plainRootMove || observedRootMove;
        if (!app && !taskMove && !rootMove) {
            System.err.println("usage: TaskDisplayAreaLaunchCommand "
                    + "app <display-id> <intent-uri> "
                    + "<left> <top> <right> <bottom> | "
                    + "move|move-observed <task-id> <source-display-id> "
                    + "<target-display-id> <left> <top> <right> <bottom> | "
                    + "move-root <task-id> <root-task-id> "
                    + "<source-display-id> <target-display-id> "
                    + "<left> <top> <right> <bottom>"
                    + " [<capture-display-id> <x> <y> <baseline-color>]");
            System.exit(64);
            return;
        }

        try {
            final int taskIdArgument = taskMove || rootMove
                    ? parseNonNegative(args[1], "task id") : -1;
            final int rootTaskIdArgument = rootMove
                    ? parseNonNegative(args[2], "root task id") : -1;
            final int sourceDisplayId = taskMove || rootMove
                    ? parseNonNegative(
                            args[rootMove ? 3 : 2], "source display id")
                    : -1;
            final int displayId = parseNonNegative(
                    args[rootMove ? 4 : taskMove ? 3 : 1], "display id");
            final Rect bounds = parseBounds(
                    args, rootMove ? 5 : taskMove ? 4 : 3);
            final DesktopTransitionSurfaceProbe.Reference surfaceReference =
                    observedRootMove
                            ? DesktopTransitionSurfaceProbe.Reference.parse(
                                    args, 9)
                            : observedMove
                                    ? DesktopTransitionSurfaceProbe.Reference
                                            .parse(args, 8)
                                    : null;
            final Object service = HiddenTaskApi.getService();
            final int taskId;
            DesktopTransitionSurfaceProbe.Result transitionObservation = null;
            if (app) {
                final Intent intent = createAppIntent(args[2]);
                taskId = launchTask(
                        service,
                        displayId,
                        intent,
                        intent.getComponent().getPackageName(),
                        bounds,
                        null,
                        null,
                        true,
                        null);
                // Keep the desktop surface visible until the cold task has
                // drawn its first freeform frame.
                ShellPreparedTaskTransition.revealFreeform(
                        service, displayId, taskId, bounds);
                waitForTaskWindowingMode(
                        service,
                        displayId,
                        taskId,
                        WINDOWING_MODE_FREEFORM);
            } else if (taskMove) {
                transitionObservation = moveExistingTask(
                        service,
                        taskIdArgument,
                        sourceDisplayId,
                        displayId,
                        bounds,
                        surfaceReference);
                taskId = taskIdArgument;
            } else {
                transitionObservation = moveRootTask(
                        service,
                        taskIdArgument,
                        rootTaskIdArgument,
                        sourceDisplayId,
                        displayId,
                        bounds,
                        surfaceReference);
                taskId = taskIdArgument;
            }
            if (transitionObservation != null) {
                System.out.println("transition-surface-changed="
                        + transitionObservation.surfaceChanged);
                System.out.println("transition-pixels="
                        + String.join(",", transitionObservation.samples));
                if (!transitionObservation.error.isEmpty()) {
                    System.out.println("transition-probe-error="
                            + transitionObservation.error.replace(
                                    '\n', ' '));
                }
            }
            System.out.println(taskMove || rootMove
                    ? "task-freeform-move=" + taskId
                    : "task-display-area-launch=" + taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("freeform task transition failed: "
                    + usefulMessage(error));
            System.err.println("transition-context: "
                    + transitionContext(args));
            System.err.println("transition-causes: "
                    + causeChain(error));
            System.exit(1);
        }
    }

    static int launchTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Rect bounds,
            final Class<?> containerTokenClass,
            final Object areaToken,
            final boolean launchBehind,
            final TransitionStartedCallback transitionCallback)
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
        if (launchBehind) {
            ActivityOptions.class.getMethod("setAvoidMoveToFront")
                    .invoke(options);
        }
        final Set<Integer> existingTaskIds = taskIdsOnDisplay(
                service, displayId);
        if (areaToken == null && transitionCallback == null) {
            launchActivity(service, intent, options);
        } else {
            // Supplying the launch as the transition's WCT lets the persistent
            // task observer join mode, bounds, and an optional organizer-owned
            // task area to the same authoritative opening transition.
            final ShellWindowTransitionExecutor.OpeningTransition transition =
                    launchPendingIntentTransition(displayId, intent, options);
            transitionCallback.onTransitionStarted(transition);
        }
        return waitForTask(
                service,
                displayId,
                -1,
                expectedPackage,
                existingTaskIds);
    }

    static int launchFullscreenTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage) throws ReflectiveOperationException {
        return launchFullscreenTask(
                service,
                displayId,
                intent,
                expectedPackage,
                null,
                null,
                ACTIVITY_TYPE_UNDEFINED);
    }

    static int launchFullscreenTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Class<?> containerTokenClass,
            final Object areaToken) throws ReflectiveOperationException {
        return launchFullscreenTask(
                service,
                displayId,
                intent,
                expectedPackage,
                containerTokenClass,
                areaToken,
                ACTIVITY_TYPE_UNDEFINED);
    }

    static int launchFullscreenTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Class<?> containerTokenClass,
            final Object areaToken,
            final int activityType) throws ReflectiveOperationException {
        if (intent == null || intent.getComponent() == null
                || (areaToken == null) != (containerTokenClass == null)) {
            throw new IllegalArgumentException(
                    "fullscreen launch requires an explicit target");
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        if (areaToken != null) {
            ActivityOptions.class.getMethod(
                    "setLaunchTaskDisplayArea", containerTokenClass)
                    .invoke(options, areaToken);
        }
        ActivityOptions.class.getMethod(
                "setLaunchWindowingMode", Integer.TYPE)
                .invoke(options, Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
        if (activityType != ACTIVITY_TYPE_UNDEFINED) {
            ActivityOptions.class.getMethod(
                    "setLaunchActivityType", Integer.TYPE)
                    .invoke(options, Integer.valueOf(activityType));
        }
        final Set<Integer> existingTaskIds = taskIdsOnDisplay(
                service, displayId);
        launchActivity(service, intent, options);
        return waitForTask(
                service,
                displayId,
                -1,
                expectedPackage,
                existingTaskIds);
    }

    static void launchTaskAction(
            final Object service,
            final int displayId,
            final int taskId,
            final String intentUri) throws ReflectiveOperationException {
        if (service == null || displayId < 0 || taskId < 0) {
            throw new IllegalArgumentException("invalid task action target");
        }
        final Intent intent = createExactAppIntent(intentUri);
        final Object task = HiddenTaskApi.findTask(service, displayId, taskId);
        if (task == null) {
            throw new IllegalArgumentException("task is unavailable: " + taskId);
        }
        final String packageName = intent.getComponent().getPackageName();
        if (!packageName.equals(HiddenTaskApi.getTaskPackage(task))) {
            throw new IllegalArgumentException(
                    "task does not belong to " + packageName);
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        // Shortcut entry activities often redirect and finish immediately.
        // Starting them inside the prepared app task keeps that redirect from
        // becoming a short-lived desktop root task.
        ActivityOptions.class.getMethod(
                "setLaunchTaskId", Integer.TYPE)
                .invoke(options, Integer.valueOf(taskId));
        launchActivity(service, intent, options);
    }

    private static ShellWindowTransitionExecutor.OpeningTransition
            launchPendingIntentTransition(
            final int displayId,
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
        return ShellWindowTransitionExecutor.beginOpening(
                displayId,
                TRANSIT_OPEN,
                transactionClass,
                transaction,
                "launch-pending-intent");
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

    static void waitForTaskWindowingMode(
            final Object service,
            final int displayId,
            final int taskId,
            final int windowingMode) throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + TASK_TIMEOUT_MILLIS;
        String observedState = "task unavailable";
        do {
            for (final Object task : HiddenTaskApi.getTasks(
                    service, displayId)) {
                if (HiddenTaskApi.getIntField(task, "taskId") == taskId) {
                    final int observedMode =
                            HiddenTaskApi.getWindowConfigurationValue(
                                    task, "getWindowingMode");
                    observedState = "display=" + displayId
                            + ", mode=" + observedMode;
                    if (observedMode == windowingMode) {
                        return;
                    }
                }
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        final Object movedTask = HiddenTaskApi.findTask(
                service, Display.INVALID_DISPLAY, taskId);
        if (movedTask != null) {
            observedState = "display="
                    + HiddenTaskApi.getTaskDisplayId(movedTask)
                    + ", mode="
                    + HiddenTaskApi.getWindowConfigurationValue(
                            movedTask, "getWindowingMode");
        }
        throw new IllegalStateException(
                "task did not enter requested windowing mode; observed "
                        + observedState + ", requested display=" + displayId
                        + ", mode=" + windowingMode);
    }

    static void waitForTaskFreeformBounds(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect expectedBounds) throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + TASK_TIMEOUT_MILLIS;
        String observedState = "task unavailable";
        do {
            for (final Object task : HiddenTaskApi.getTasks(
                    service, displayId)) {
                if (HiddenTaskApi.getIntField(task, "taskId") == taskId) {
                    final int windowingMode =
                            HiddenTaskApi.getWindowConfigurationValue(
                                    task, "getWindowingMode");
                    final Object windowConfiguration =
                            HiddenTaskApi.getWindowConfiguration(task);
                    final Rect bounds = (Rect) windowConfiguration.getClass()
                            .getMethod("getBounds")
                            .invoke(windowConfiguration);
                    observedState = "mode=" + windowingMode
                            + ", bounds=" + bounds;
                    if (windowingMode != WINDOWING_MODE_FREEFORM) {
                        continue;
                    }
                    if (satisfiesRequestedBounds(bounds, expectedBounds)) {
                        return;
                    }
                }
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "task did not reach the requested freeform bounds; observed "
                        + observedState + ", requested=" + expectedBounds);
    }

    static boolean satisfiesRequestedBounds(
            final Rect observedBounds,
            final Rect requestedBounds) {
        return observedBounds != null
                && requestedBounds != null
                && satisfiesRequestedBounds(
                        observedBounds.left,
                        observedBounds.top,
                        observedBounds.right,
                        observedBounds.bottom,
                        requestedBounds.left,
                        requestedBounds.top,
                        requestedBounds.right,
                        requestedBounds.bottom);
    }

    static boolean satisfiesRequestedBounds(
            final int observedLeft,
            final int observedTop,
            final int observedRight,
            final int observedBottom,
            final int requestedLeft,
            final int requestedTop,
            final int requestedRight,
            final int requestedBottom) {
        return observedRight > observedLeft
                && observedBottom > observedTop
                && requestedRight > requestedLeft
                && requestedBottom > requestedTop
                // WindowManager may enlarge an app window to satisfy its
                // declared minimum dimensions while preserving the requested
                // area. Treat that constrained result as a successful move.
                && observedLeft <= requestedLeft
                && observedTop <= requestedTop
                && observedRight >= requestedRight
                && observedBottom >= requestedBottom;
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

    static Intent createAppIntent(final String intentUri) {
        final Intent intent = createExactAppIntent(intentUri);
        return intent.addFlags(additionalLaunchFlags(intent.getFlags()))
                .putExtra("start_from_heartservice_app_lock", true);
    }

    static Intent createExactAppIntent(final String intentUri) {
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
        return intent.putExtra("start_from_heartservice_app_lock", true);
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

    private static DesktopTransitionSurfaceProbe.Result moveExistingTask(
            final Object service,
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds,
            final DesktopTransitionSurfaceProbe.Reference reference)
            throws ReflectiveOperationException {
        final DesktopTransitionSurfaceProbe.Observation observation =
                reference == null
                        ? null : DesktopTransitionSurfaceProbe.begin(reference);
        if (observation != null) {
            observation.sample("before");
        }
        moveExistingTask(
                service,
                taskId,
                sourceDisplayId,
                targetDisplayId,
                bounds);
        waitForTaskFreeformBounds(
                service, targetDisplayId, taskId, bounds);
        if (observation != null) {
            observation.sample("settled");
        }
        return observation == null ? null : observation.finish();
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
        ShellWindowTransitionExecutor.playSystemTransition(
                targetDisplayId,
                ShellWindowTransitionExecutor.SystemTransition.OPEN,
                transactionClass,
                transaction,
                "move-running-task");
    }

    private static DesktopTransitionSurfaceProbe.Result moveRootTask(
            final Object service,
            final int taskId,
            final int rootTaskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds,
            final DesktopTransitionSurfaceProbe.Reference reference)
            throws ReflectiveOperationException {
        final Object originalTask = HiddenTaskApi.requireTask(
                service, sourceDisplayId, taskId);
        final int originalWindowingMode =
                HiddenTaskApi.getWindowConfigurationValue(
                        originalTask, "getWindowingMode");
        final Object originalWindowConfiguration =
                HiddenTaskApi.getWindowConfiguration(originalTask);
        final Rect originalBounds = new Rect(
                (Rect) originalWindowConfiguration.getClass()
                        .getMethod("getBounds")
                        .invoke(originalWindowConfiguration));
        final DesktopTransitionSurfaceProbe.Observation observation =
                reference == null
                        ? null : DesktopTransitionSurfaceProbe.begin(reference);
        boolean taskHidden = false;
        try {
            if (observation != null) {
                observation.sample("before");
            }
            taskHidden = true;
            // Keep the task hidden in fullscreen while its root crosses the
            // display boundary. Revealing freeform only on the target gives
            // WMShell a real target-local mode transition, so it rebuilds
            // caption surfaces and input windows on that display.
            ShellPreparedTaskTransition.prepareFullscreen(
                    service, sourceDisplayId, taskId);
            waitForTaskWindowingMode(
                    service,
                    sourceDisplayId,
                    taskId,
                    WINDOWING_MODE_FULLSCREEN);
            waitForTaskVisibility(
                    service, sourceDisplayId, taskId, false);
            final boolean sourcePreparedVisible =
                    HiddenTaskApi.getBooleanField(
                            HiddenTaskApi.requireTask(
                                    service, sourceDisplayId, taskId),
                            "isVisible");
            System.out.println("source-prepared-visible="
                    + sourcePreparedVisible);
            if (sourcePreparedVisible) {
                throw new IllegalStateException(
                        "prepared source task remained visible");
            }
            if (observation != null) {
                observation.sample("prepared");
            }
            service.getClass().getMethod(
                    "moveRootTaskToDisplay", Integer.TYPE, Integer.TYPE)
                    .invoke(
                            service,
                            Integer.valueOf(rootTaskId),
                            Integer.valueOf(targetDisplayId));
            waitForTaskWindowingMode(
                    service,
                    targetDisplayId,
                    taskId,
                    WINDOWING_MODE_FULLSCREEN);
            waitForTaskVisibility(
                    service, targetDisplayId, taskId, false);
            final boolean targetPreparedVisible =
                    HiddenTaskApi.getBooleanField(
                            HiddenTaskApi.requireTask(
                                    service, targetDisplayId, taskId),
                            "isVisible");
            System.out.println("target-prepared-visible="
                    + targetPreparedVisible);
            if (targetPreparedVisible) {
                throw new IllegalStateException(
                        "prepared target task became visible");
            }
            if (observation != null) {
                observation.sample("moved");
            }
            ShellPreparedTaskTransition.showPreparedFreeform(
                    service, targetDisplayId, taskId, bounds);
            waitForTaskFreeformBounds(
                    service, targetDisplayId, taskId, bounds);
            waitForTaskVisibility(
                    service, targetDisplayId, taskId, true);
            taskHidden = false;
            if (observation != null) {
                observation.sample("settled");
            }
            return observation == null ? null : observation.finish();
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (taskHidden) {
                try {
                    restoreFailedRootTaskMove(
                            service,
                            taskId,
                            rootTaskId,
                            sourceDisplayId,
                            originalWindowingMode,
                            originalBounds);
                } catch (ReflectiveOperationException
                        | RuntimeException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
            }
            throw error;
        }
    }

    private static void restoreFailedRootTaskMove(
            final Object service,
            final int taskId,
            final int rootTaskId,
            final int sourceDisplayId,
            final int originalWindowingMode,
            final Rect originalBounds) throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.findTask(
                service, Display.INVALID_DISPLAY, taskId);
        if (task == null) {
            return;
        }
        if (HiddenTaskApi.getTaskDisplayId(task) != sourceDisplayId) {
            service.getClass().getMethod(
                    "moveRootTaskToDisplay", Integer.TYPE, Integer.TYPE)
                    .invoke(
                            service,
                            Integer.valueOf(rootTaskId),
                            Integer.valueOf(sourceDisplayId));
            waitForTask(
                    service, sourceDisplayId, taskId, null, null);
        }
        ShellPreparedTaskTransition.restorePreparedTask(
                service,
                sourceDisplayId,
                taskId,
                originalWindowingMode,
                originalBounds);
    }

    static void waitForTaskVisibility(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean visible) throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + TASK_TIMEOUT_MILLIS;
        do {
            final Object task = HiddenTaskApi.findTask(
                    service, displayId, taskId);
            if (task != null
                    && HiddenTaskApi.getBooleanField(task, "isVisible")
                            == visible) {
                return;
            }
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "task did not become "
                        + (visible ? "visible" : "hidden"));
    }

    private static Rect parseBounds(
            final String[] args,
            final int offset) {
        final Rect bounds = new Rect(
                Integer.parseInt(args[offset]),
                Integer.parseInt(args[offset + 1]),
                Integer.parseInt(args[offset + 2]),
                Integer.parseInt(args[offset + 3]));
        if (bounds.left < 0 || bounds.top < 0
                || !hasExplicitBounds(bounds)) {
            throw new IllegalArgumentException("invalid bounds");
        }
        return bounds;
    }

    private static boolean hasExplicitBounds(final Rect bounds) {
        return bounds != null
                && bounds.right > bounds.left
                && bounds.bottom > bounds.top;
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

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    static String transitionContext(final String[] args) {
        if (args.length == 7 && "app".equals(args[0])) {
            return "operation=app, targetDisplay=" + args[1]
                    + ", bounds=" + argumentBounds(args, 3);
        }
        if ((args.length == 8 && "move".equals(args[0]))
                || (args.length == 12
                        && "move-observed".equals(args[0]))) {
            return "operation=" + args[0]
                    + ", task=" + args[1]
                    + ", sourceDisplay=" + args[2]
                    + ", targetDisplay=" + args[3]
                    + ", bounds=" + argumentBounds(args, 4);
        }
        if ((args.length == 9 && "move-root".equals(args[0]))
                || (args.length == 13
                        && "move-root-observed".equals(args[0]))) {
            return "operation=" + args[0]
                    + ", task=" + args[1]
                    + ", rootTask=" + args[2]
                    + ", sourceDisplay=" + args[3]
                    + ", targetDisplay=" + args[4]
                    + ", bounds=" + argumentBounds(args, 5);
        }
        return "operation=unknown, argumentCount=" + args.length;
    }

    private static String argumentBounds(
            final String[] args,
            final int offset) {
        return "[" + args[offset]
                + "," + args[offset + 1]
                + "][" + args[offset + 2]
                + "," + args[offset + 3] + "]";
    }

    static String causeChain(final Throwable error) {
        final StringBuilder result = new StringBuilder();
        final Set<Throwable> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        Throwable cause = error;
        while (cause != null && visited.add(cause)) {
            if (result.length() > 0) {
                result.append(" -> ");
            }
            result.append(cause.getClass().getName());
            final String message = cause.getMessage();
            if (message != null && !message.isEmpty()) {
                final String singleLine = message.replace('\n', ' ');
                result.append(": ").append(
                        singleLine.length() <= FAILURE_MESSAGE_LIMIT
                                ? singleLine
                                : singleLine.substring(
                                        0, FAILURE_MESSAGE_LIMIT) + "...");
            }
            cause = cause.getCause();
        }
        return result.toString();
    }
}
