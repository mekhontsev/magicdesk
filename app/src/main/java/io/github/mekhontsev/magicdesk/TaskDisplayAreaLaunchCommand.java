package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Launches or moves a live task into its requested desktop hierarchy. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class TaskDisplayAreaLaunchCommand {
    interface TaskIdSource {
        void onBeforeLaunch(TaskLaunchBaseline baseline);

        int awaitTaskId(long timeoutMillis);
    }

    static final class TaskLaunchBaseline {
        final Set<Integer> targetDisplayTaskIds;
        final Set<Integer> allTaskIds;

        TaskLaunchBaseline(
                final Set<Integer> targetDisplayTaskIds,
                final Set<Integer> allTaskIds) {
            if (targetDisplayTaskIds == null || allTaskIds == null
                    || !allTaskIds.containsAll(targetDisplayTaskIds)) {
                throw new IllegalArgumentException(
                        "invalid pre-launch task baseline");
            }
            this.targetDisplayTaskIds = immutableIds(targetDisplayTaskIds);
            this.allTaskIds = immutableIds(allTaskIds);
        }

        private static Set<Integer> immutableIds(final Set<Integer> taskIds) {
            return Collections.unmodifiableSet(new HashSet<>(taskIds));
        }
    }

    interface ActivityStarter {
        void start(ActivityOptions options) throws ReflectiveOperationException;
    }

    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";
    private static final String ACTIVITY_CLASS =
            "io.github.mekhontsev.magicdesk.DesktopSelfTestActivity";
    private static final String BROWSER_ACTIVITY_CLASS =
            "io.github.mekhontsev.magicdesk.DesktopSelfTestBrowserActivity";
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

    public static void main(final String[] args) {
        final boolean app = args.length == 7 && "app".equals(args[0]);
        final boolean move = args.length == 8 && "move".equals(args[0]);
        final boolean observedMove = args.length == 12
                && "move-observed".equals(args[0]);
        final boolean taskMove = move || observedMove;
        if (!app && !taskMove) {
            System.err.println("usage: TaskDisplayAreaLaunchCommand "
                    + "app <display-id> <intent-uri> "
                    + "<left> <top> <right> <bottom> | "
                    + "move|move-observed <task-id> <source-display-id> "
                    + "<target-display-id> <left> <top> <right> <bottom>"
                    + " [<capture-display-id> <x> <y> <baseline-color>]");
            System.exit(64);
            return;
        }

        try {
            final int taskIdArgument = taskMove
                    ? parseNonNegative(args[1], "task id") : -1;
            final int sourceDisplayId = taskMove
                    ? parseNonNegative(args[2], "source display id")
                    : -1;
            final int displayId = parseNonNegative(
                    args[taskMove ? 3 : 1], "display id");
            final Rect bounds = parseBounds(
                    args, taskMove ? 4 : 3);
            final DesktopTransitionSurfaceProbe.Reference surfaceReference =
                    observedMove
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
                        true);
                // Keep the desktop surface visible until the cold task has
                // drawn its first freeform frame.
                ShellPreparedTaskTransition.revealFreeform(
                        service, displayId, taskId, bounds);
                waitForTaskWindowingMode(
                        service,
                        displayId,
                        taskId,
                        WINDOWING_MODE_FREEFORM);
            } else {
                transitionObservation = moveExistingTask(
                        service,
                        taskIdArgument,
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
            System.out.println(taskMove
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
            final Object areaToken,
            final boolean launchBehind)
            throws ReflectiveOperationException {
        return launchTask(
                service,
                displayId,
                intent,
                expectedPackage,
                bounds,
                areaToken,
                launchBehind,
                null);
    }

    static int launchTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Rect bounds,
            final Object areaToken,
            final boolean launchBehind,
            final TaskIdSource taskIdSource)
            throws ReflectiveOperationException {
        return launchTask(
                service,
                displayId,
                expectedPackage,
                bounds,
                areaToken,
                launchBehind,
                taskIdSource,
                options -> launchActivity(service, intent, options));
    }

    static int launchPendingIntentTask(
            final Object service,
            final int displayId,
            final String expectedPackage,
            final PendingIntent pendingIntent,
            final Rect bounds,
            final Object areaToken,
            final boolean launchBehind,
            final TaskIdSource taskIdSource,
            final IActivityLaunchCallback activityLauncher)
            throws ReflectiveOperationException {
        if (pendingIntent == null || activityLauncher == null) {
            throw new IllegalArgumentException(
                    "pending intent and activity launcher are required");
        }
        return launchTask(
                service,
                displayId,
                expectedPackage,
                bounds,
                areaToken,
                launchBehind,
                taskIdSource,
                options -> sendPendingIntent(
                        activityLauncher, pendingIntent, options));
    }

    static int launchCreatorAuthorizedPendingIntentTask(
            final Object service,
            final int displayId,
            final String expectedPackage,
            final PendingIntent pendingIntent,
            final Rect bounds,
            final Object areaToken,
            final boolean launchBehind,
            final TaskIdSource taskIdSource)
            throws ReflectiveOperationException {
        if (pendingIntent == null) {
            throw new IllegalArgumentException("pending intent is required");
        }
        return launchTask(
                service,
                displayId,
                expectedPackage,
                bounds,
                areaToken,
                launchBehind,
                taskIdSource,
                options -> sendCreatorAuthorizedPendingIntent(
                        pendingIntent, options));
    }

    private static int launchTask(
            final Object service,
            final int displayId,
            final String expectedPackage,
            final Rect bounds,
            final Object areaToken,
            final boolean launchBehind,
            final TaskIdSource taskIdSource,
            final ActivityStarter starter)
            throws ReflectiveOperationException {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        options.setLaunchBounds(bounds);
        if (areaToken != null) {
            ActivityOptions.class.getMethod(
                    "setLaunchTaskDisplayArea",
                    FrameworkRuntime.current().windowing().tokenClass())
                    .invoke(options, areaToken);
        }
        ActivityOptions.class.getMethod(
                "setLaunchWindowingMode", Integer.TYPE)
                .invoke(options, Integer.valueOf(WINDOWING_MODE_FREEFORM));
        ActivityOptions.class.getMethod(
                "setLaunchActivityType", Integer.TYPE)
                .invoke(options, Integer.valueOf(
                        FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD));
        if (launchBehind) {
            ActivityOptions.class.getMethod("setAvoidMoveToFront")
                    .invoke(options);
        }
        final TaskLaunchBaseline baseline = taskBaselineBeforeLaunch(
                service, displayId);
        prepareTaskIdSource(taskIdSource, baseline);
        // The task token does not exist yet. Staged callers keep the provisional
        // root behind the desktop and publish its final geometry after the
        // token is known; other callers can rely on these launch options alone.
        starter.start(options);
        return waitForTask(
                service,
                displayId,
                -1,
                expectedPackage,
                baseline.targetDisplayTaskIds,
                taskIdSource);
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
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD);
    }

    static int launchFullscreenTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Object areaToken) throws ReflectiveOperationException {
        return launchFullscreenTask(
                service,
                displayId,
                intent,
                expectedPackage,
                areaToken,
                null);
    }

    static int launchFullscreenTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Object areaToken,
            final TaskIdSource taskIdSource)
            throws ReflectiveOperationException {
        if (intent == null || intent.getComponent() == null) {
            throw new IllegalArgumentException(
                    "fullscreen launch requires an explicit target");
        }
        return launchFullscreenTask(
                service,
                displayId,
                expectedPackage,
                areaToken,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                false,
                taskIdSource,
                options -> launchActivity(service, intent, options));
    }

    static int launchFullscreenTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Object areaToken,
            final int activityType) throws ReflectiveOperationException {
        return launchFullscreenTask(
                service,
                displayId,
                intent,
                expectedPackage,
                areaToken,
                activityType,
                false);
    }

    static int launchFullscreenTaskBehind(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Object areaToken) throws ReflectiveOperationException {
        return launchFullscreenTask(
                service,
                displayId,
                intent,
                expectedPackage,
                areaToken,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                true);
    }

    static int launchFullscreenTaskBehind(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Object areaToken,
            final int activityType) throws ReflectiveOperationException {
        return launchFullscreenTask(
                service,
                displayId,
                intent,
                expectedPackage,
                areaToken,
                activityType,
                true);
    }

    private static int launchFullscreenTask(
            final Object service,
            final int displayId,
            final Intent intent,
            final String expectedPackage,
            final Object areaToken,
            final int activityType,
            final boolean launchBehind) throws ReflectiveOperationException {
        if (intent == null || intent.getComponent() == null) {
            throw new IllegalArgumentException(
                    "fullscreen launch requires an explicit target");
        }
        return launchFullscreenTask(
                service,
                displayId,
                expectedPackage,
                areaToken,
                activityType,
                launchBehind,
                null,
                options -> launchActivity(service, intent, options));
    }

    static int launchFullscreenPendingIntentTask(
            final Object service,
            final int displayId,
            final String expectedPackage,
            final PendingIntent pendingIntent,
            final Object areaToken,
            final TaskIdSource taskIdSource,
            final IActivityLaunchCallback activityLauncher)
            throws ReflectiveOperationException {
        if (pendingIntent == null || activityLauncher == null) {
            throw new IllegalArgumentException(
                    "pending intent and activity launcher are required");
        }
        return launchFullscreenTask(
                service,
                displayId,
                expectedPackage,
                areaToken,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                false,
                taskIdSource,
                options -> sendPendingIntent(
                        activityLauncher, pendingIntent, options));
    }

    static int launchFullscreenCreatorAuthorizedPendingIntentTask(
            final Object service,
            final int displayId,
            final String expectedPackage,
            final PendingIntent pendingIntent,
            final Object areaToken,
            final TaskIdSource taskIdSource)
            throws ReflectiveOperationException {
        if (pendingIntent == null) {
            throw new IllegalArgumentException("pending intent is required");
        }
        return launchFullscreenTask(
                service,
                displayId,
                expectedPackage,
                areaToken,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                false,
                taskIdSource,
                options -> sendCreatorAuthorizedPendingIntent(
                        pendingIntent, options));
    }

    private static int launchFullscreenTask(
            final Object service,
            final int displayId,
            final String expectedPackage,
            final Object areaToken,
            final int activityType,
            final boolean launchBehind,
            final TaskIdSource taskIdSource,
            final ActivityStarter starter)
            throws ReflectiveOperationException {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        if (areaToken != null) {
            ActivityOptions.class.getMethod(
                    "setLaunchTaskDisplayArea",
                    FrameworkRuntime.current().windowing().tokenClass())
                    .invoke(options, areaToken);
        }
        ActivityOptions.class.getMethod(
                "setLaunchWindowingMode", Integer.TYPE)
                .invoke(options, Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
        if (activityType != FrameworkTaskSnapshot.ACTIVITY_TYPE_UNDEFINED) {
            ActivityOptions.class.getMethod(
                    "setLaunchActivityType", Integer.TYPE)
                    .invoke(options, Integer.valueOf(activityType));
        }
        if (launchBehind) {
            ActivityOptions.class.getMethod("setAvoidMoveToFront")
                    .invoke(options);
        }
        final TaskLaunchBaseline baseline = taskBaselineBeforeLaunch(
                service, displayId);
        prepareTaskIdSource(taskIdSource, baseline);
        starter.start(options);
        return waitForTask(
                service,
                displayId,
                -1,
                expectedPackage,
                baseline.targetDisplayTaskIds,
                taskIdSource);
    }

    static void launchPendingIntentTaskAction(
            final int displayId,
            final int taskId,
            final PendingIntent pendingIntent,
            final IActivityLaunchCallback activityLauncher)
            throws ReflectiveOperationException {
        if (displayId < 0 || taskId < 0 || pendingIntent == null
                || activityLauncher == null) {
            throw new IllegalArgumentException(
                    "invalid pending intent task target");
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        ActivityOptions.class.getMethod(
                "setLaunchTaskId", Integer.TYPE)
                .invoke(options, Integer.valueOf(taskId));
        sendPendingIntent(activityLauncher, pendingIntent, options);
    }

    static void launchCreatorAuthorizedPendingIntentTaskAction(
            final int displayId,
            final int taskId,
            final PendingIntent pendingIntent)
            throws ReflectiveOperationException {
        if (displayId < 0 || taskId < 0 || pendingIntent == null) {
            throw new IllegalArgumentException(
                    "invalid pending intent task target");
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        ActivityOptions.class.getMethod(
                "setLaunchTaskId", Integer.TYPE)
                .invoke(options, Integer.valueOf(taskId));
        sendCreatorAuthorizedPendingIntent(pendingIntent, options);
    }

    private static void sendPendingIntent(
            final IActivityLaunchCallback activityLauncher,
            final PendingIntent pendingIntent,
            final ActivityOptions options) throws ReflectiveOperationException {
        AndroidPendingIntentOptions.allowSenderStart(options, true);
        try {
            activityLauncher.sendPendingIntent(
                    pendingIntent, options.toBundle());
        } catch (android.os.RemoteException error) {
            throw new ReflectiveOperationException(
                    "visible activity launcher is unavailable", error);
        }
    }

    private static void sendCreatorAuthorizedPendingIntent(
            final PendingIntent pendingIntent,
            final ActivityOptions options) throws ReflectiveOperationException {
        AndroidPendingIntentOptions.allowSenderStart(options, false);
        try {
            // PendingIntent's creator authorizes the target and URI grants;
            // this shell caller contributes only privileged task options.
            pendingIntent.send(
                    null,
                    0,
                    null,
                    null,
                    null,
                    null,
                    options.toBundle());
        } catch (PendingIntent.CanceledException | RuntimeException error) {
            throw new ReflectiveOperationException(
                    "creator-authorized Activity launch failed", error);
        }
    }

    static void launchTaskAction(
            final Object service,
            final int displayId,
            final int taskId,
            final Intent sourceIntent) throws ReflectiveOperationException {
        if (service == null || displayId < 0 || taskId < 0) {
            throw new IllegalArgumentException("invalid task action target");
        }
        final Intent intent = createExactAppIntent(sourceIntent);
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
        return waitForTask(
                service,
                displayId,
                expectedTaskId,
                expectedPackage,
                excludedTaskIds,
                null);
    }

    private static int waitForTask(
            final Object service,
            final int displayId,
            final int expectedTaskId,
            final String expectedPackage,
            final Set<Integer> excludedTaskIds,
            final TaskIdSource taskIdSource)
            throws ReflectiveOperationException {
        if (taskIdSource != null) {
            final int observedTaskId = taskIdSource.awaitTaskId(50L);
            if (isFreshTaskId(observedTaskId, excludedTaskIds)) {
                return observedTaskId;
            }
        }
        final List<FrameworkTaskSnapshot> tasks =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_APPEARANCE,
                        TASK_TIMEOUT_MILLIS,
                        50L,
                        () -> FrameworkTaskSnapshotSource.readWindowState(
                                service, displayId, 100),
                        current -> findMatchingTask(
                                current,
                                expectedTaskId,
                                expectedPackage,
                                excludedTaskIds) != null);
        final FrameworkTaskSnapshot task = findMatchingTask(
                tasks, expectedTaskId, expectedPackage, excludedTaskIds);
        if (task != null) {
            return task.taskId;
        }
        throw new IllegalStateException("task did not appear");
    }

    static void waitForTaskWindowingMode(
            final Object service,
            final int displayId,
            final int taskId,
            final int windowingMode) throws ReflectiveOperationException {
        final FrameworkTaskSnapshot settled =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_WINDOWING_MODE,
                        TASK_TIMEOUT_MILLIS,
                        50L,
                        () -> FrameworkTaskSnapshotSource.findTask(
                                service, displayId, taskId),
                        task -> task != null
                                && task.windowingMode == windowingMode);
        if (settled != null && settled.windowingMode == windowingMode) {
            return;
        }
        String observedState = "task unavailable";
        final FrameworkTaskSnapshot movedTask =
                FrameworkTaskSnapshotSource.findTask(
                        service, Display.INVALID_DISPLAY, taskId);
        if (movedTask != null) {
            observedState = "display=" + movedTask.displayId
                    + ", mode=" + movedTask.windowingMode;
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
        final FrameworkTaskSnapshot task =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_BOUNDS,
                        TASK_TIMEOUT_MILLIS,
                        50L,
                        () -> FrameworkTaskSnapshotSource.findTask(
                                service, displayId, taskId),
                        current -> current != null
                                && current.windowingMode
                                        == WINDOWING_MODE_FREEFORM
                                && satisfiesRequestedBounds(
                                        current.bounds, expectedBounds));
        if (task != null
                && task.windowingMode == WINDOWING_MODE_FREEFORM
                && satisfiesRequestedBounds(task.bounds, expectedBounds)) {
            return;
        }
        final String observedState = task == null
                ? "task unavailable"
                : "mode=" + task.windowingMode
                        + ", bounds=" + task.bounds;
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

    private static TaskLaunchBaseline taskBaselineBeforeLaunch(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        final Set<Integer> targetDisplayTaskIds = new HashSet<>();
        final Set<Integer> allTaskIds = new HashSet<>();
        for (final Object task : HiddenTaskApi.getAllTasks(service)) {
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getTaskId(task));
            allTaskIds.add(taskId);
            if (HiddenTaskApi.getTaskDisplayId(task) == displayId) {
                targetDisplayTaskIds.add(taskId);
            }
        }
        return new TaskLaunchBaseline(targetDisplayTaskIds, allTaskIds);
    }

    private static void prepareTaskIdSource(
            final TaskIdSource taskIdSource,
            final TaskLaunchBaseline baseline) {
        if (taskIdSource != null) {
            taskIdSource.onBeforeLaunch(baseline);
        }
    }

    static boolean isFreshTaskId(
            final int taskId,
            final Set<Integer> existingTaskIds) {
        return taskId >= 0
                && existingTaskIds != null
                && !existingTaskIds.contains(Integer.valueOf(taskId));
    }

    static Intent createAppIntent(final String intentUri) {
        return createAppIntent(createExactAppIntent(intentUri));
    }

    static Intent createAppIntent(final Intent sourceIntent) {
        final Intent intent = createExactAppIntent(sourceIntent);
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
        return createExactAppIntent(intent);
    }

    static Intent createExactAppIntent(final Intent sourceIntent) {
        if (sourceIntent == null) {
            throw new IllegalArgumentException("missing app launch intent");
        }
        final Intent intent = new Intent(sourceIntent);
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
        final Object originalTask = HiddenTaskApi.requireTask(
                service, sourceDisplayId, taskId);
        final int originalWindowingMode =
                HiddenTaskApi.getTaskWindowingMode(originalTask);
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
            // A source-side freeform state can reconfigure the client before
            // it moves. Keep the task hidden and fullscreen until the target
            // OPEN transaction applies display, mode, bounds and visibility.
            ShellPreparedTaskTransition.prepareFullscreen(
                    service, sourceDisplayId, taskId);
            waitForTaskWindowingMode(
                    service,
                    sourceDisplayId,
                    taskId,
                    WINDOWING_MODE_FULLSCREEN);
            waitForTaskVisibility(
                    service, sourceDisplayId, taskId, false);
            if (observation != null) {
                observation.sample("prepared");
            }
            moveExistingTaskAsFreeform(
                    service,
                    taskId,
                    sourceDisplayId,
                    targetDisplayId,
                    bounds);
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
                    restoreFailedTaskMove(
                            service,
                            taskId,
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

    static void moveExistingTaskAsFreeform(
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
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        // Apply the display move, mode and geometry in one visible change. The
        // task already exists, so OPEN would add a launch animation and alter
        // the desktop surface while the task crosses displays.
        windowing.setWindowingMode(
                transaction, taskToken, WINDOWING_MODE_FREEFORM);
        windowing.setBounds(transaction, taskToken, bounds);
        windowing.setForceTranslucent(transaction, taskToken, false);
        windowing.setHidden(transaction, taskToken, false);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction,
                taskToken,
                false);
        windowing.startTask(transaction, taskId, options.toBundle());
        ShellWindowTransitionExecutor.startForShellAdoption(
                targetDisplayId,
                ShellWindowTransitionExecutor.SystemTransition.CHANGE,
                transactionClass,
                transaction,
                "move-running-task");
    }

    private static void restoreFailedTaskMove(
            final Object service,
            final int taskId,
            final int sourceDisplayId,
            final int originalWindowingMode,
            final Rect originalBounds) throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.findTask(
                service, Display.INVALID_DISPLAY, taskId);
        if (task == null) {
            return;
        }
        final int currentDisplayId = HiddenTaskApi.getTaskDisplayId(task);
        if (currentDisplayId == sourceDisplayId) {
            ShellPreparedTaskTransition.restorePreparedTask(
                    service,
                    sourceDisplayId,
                    taskId,
                    originalWindowingMode,
                    originalBounds);
            return;
        }
        if (originalWindowingMode == WINDOWING_MODE_FREEFORM) {
            moveExistingTaskAsFreeform(
                    service,
                    taskId,
                    currentDisplayId,
                    sourceDisplayId,
                    originalBounds);
            waitForTaskFreeformBounds(
                    service, sourceDisplayId, taskId, originalBounds);
        } else if (originalWindowingMode == WINDOWING_MODE_FULLSCREEN) {
            moveExistingTaskAsFullscreen(
                    service, taskId, currentDisplayId, sourceDisplayId);
            waitForTaskWindowingMode(
                    service,
                    sourceDisplayId,
                    taskId,
                    WINDOWING_MODE_FULLSCREEN);
        } else {
            throw new IllegalStateException(
                    "cannot restore task windowing mode "
                            + originalWindowingMode);
        }
        waitForTaskVisibility(service, sourceDisplayId, taskId, true);
    }

    static void moveExistingTaskAsFullscreen(
            final Object service,
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws ReflectiveOperationException {
        HiddenTaskApi.requireTask(service, sourceDisplayId, taskId);
        final ActivityOptions options = existingTaskOptions(
                targetDisplayId,
                WINDOWING_MODE_FULLSCREEN,
                null,
                null);
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                service, sourceDisplayId, taskId);
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        windowing.setWindowingMode(
                transaction, taskToken, WINDOWING_MODE_FULLSCREEN);
        windowing.setBounds(transaction, taskToken, new Rect());
        windowing.setForceTranslucent(transaction, taskToken, false);
        windowing.setHidden(transaction, taskToken, false);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction,
                taskToken,
                true);
        windowing.startTask(transaction, taskId, options.toBundle());
        ShellWindowTransitionExecutor.startForShellAdoption(
                targetDisplayId,
                ShellWindowTransitionExecutor.SystemTransition.OPEN,
                transactionClass,
                transaction,
                "move-running-task-fullscreen");
    }

    /**
     * Lets ActivityTaskManager move a live task out of an organizer-owned
     * fullscreen area while applying its freeform launch configuration.
     */
    static void restartExistingTaskAsFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) throws ReflectiveOperationException {
        if (service == null || displayId < 0 || taskId < 0
                || !hasExplicitBounds(bounds)) {
            throw new IllegalArgumentException(
                    "invalid existing task freeform restart");
        }
        final ActivityOptions options = existingTaskOptions(
                displayId,
                WINDOWING_MODE_FREEFORM,
                bounds,
                null);
        restartExistingTask(service, displayId, taskId, options);
    }

    /** Moves a live task into an organizer area through Android task focus. */
    static void moveExistingTaskAsFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final Object areaToken) throws ReflectiveOperationException {
        if (service == null || displayId < 0 || taskId < 0
                || areaToken == null) {
            throw new IllegalArgumentException(
                    "invalid existing task fullscreen move");
        }
        final Object task = HiddenTaskApi.requireTask(
                service, displayId, taskId);
        final Object windowConfiguration =
                HiddenTaskApi.getWindowConfiguration(task);
        final Rect fullscreenBounds = new Rect(
                (Rect) windowConfiguration.getClass()
                        .getMethod("getMaxBounds")
                        .invoke(windowConfiguration));
        if (fullscreenBounds.isEmpty()) {
            throw new IllegalStateException(
                    "fullscreen task max bounds are unavailable");
        }
        final ActivityOptions options = existingTaskOptions(
                displayId,
                WINDOWING_MODE_FULLSCREEN,
                fullscreenBounds,
                areaToken);
        final Class<?> applicationThreadClass =
                Class.forName("android.app.IApplicationThread");
        service.getClass().getMethod(
                "moveTaskToFront",
                applicationThreadClass,
                String.class,
                Integer.TYPE,
                Integer.TYPE,
                Bundle.class)
                .invoke(
                        service,
                        null,
                        "com.android.shell",
                        Integer.valueOf(taskId),
                        Integer.valueOf(0),
                        options.toBundle());
    }

    static void focusExistingTask(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.requireTask(
                service, displayId, taskId);
        final Object configuration = HiddenTaskApi.getWindowConfiguration(
                task);
        final Rect bounds = new Rect(
                (Rect) configuration.getClass()
                        .getMethod("getBounds")
                        .invoke(configuration));
        final int windowingMode = HiddenTaskApi.getTaskWindowingMode(task);
        final ActivityOptions options = existingTaskOptions(
                displayId,
                windowingMode,
                bounds,
                null);
        // Recents activation is observed by WMShell and releases any native
        // minimize state attached to the root task's surface. A raw
        // moveTaskToFront only changes ATMS hierarchy and can leave that
        // surface hidden behind the desktop host.
        restartExistingTask(service, displayId, taskId, options);
    }

    private static ActivityOptions existingTaskOptions(
            final int displayId,
            final int windowingMode,
            final Rect bounds,
            final Object areaToken) throws ReflectiveOperationException {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        if (bounds != null) {
            options.setLaunchBounds(new Rect(bounds));
        }
        if (areaToken != null) {
            ActivityOptions.class.getMethod(
                    "setLaunchTaskDisplayArea",
                    FrameworkRuntime.current().windowing().tokenClass())
                    .invoke(options, areaToken);
        }
        ActivityOptions.class.getMethod(
                "setLaunchWindowingMode", Integer.TYPE)
                .invoke(options, Integer.valueOf(windowingMode));
        ActivityOptions.class.getMethod(
                "setFlexibleLaunchSize", Boolean.TYPE)
                .invoke(options, Boolean.TRUE);
        return options;
    }

    private static void restartExistingTask(
            final Object service,
            final int displayId,
            final int taskId,
            final ActivityOptions options) throws ReflectiveOperationException {
        HiddenTaskApi.requireTask(service, displayId, taskId);
        final Object result = service.getClass().getMethod(
                "startActivityFromRecents", Integer.TYPE, Bundle.class)
                .invoke(
                        service,
                        Integer.valueOf(taskId),
                        options.toBundle());
        if (!(result instanceof Integer)
                || ((Integer) result).intValue() < 0) {
            throw new IllegalStateException(
                    "existing task restart failed: " + result);
        }
    }

    static void waitForTaskVisibility(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean visible) throws ReflectiveOperationException {
        final FrameworkTaskSnapshot task =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_VISIBILITY,
                        TASK_TIMEOUT_MILLIS,
                        50L,
                        () -> FrameworkTaskSnapshotSource.findTask(
                                service, displayId, taskId),
                        current -> current != null
                                && current.visible == visible);
        if (task != null && task.visible == visible) {
            return;
        }
        throw new IllegalStateException(
                "task did not become "
                        + (visible ? "visible" : "hidden"));
    }

    private static FrameworkTaskSnapshot findMatchingTask(
            final List<FrameworkTaskSnapshot> tasks,
            final int expectedTaskId,
            final String expectedPackage,
            final Set<Integer> excludedTaskIds) {
        if (tasks == null) {
            return null;
        }
        for (final FrameworkTaskSnapshot task : tasks) {
            final boolean matches = expectedTaskId >= 0
                    ? task.taskId == expectedTaskId
                    : expectedPackage != null
                            && (excludedTaskIds == null
                                    || !excludedTaskIds.contains(
                                            Integer.valueOf(task.taskId)))
                            && expectedPackage.equals(task.packageName);
            if (matches) {
                return task;
            }
        }
        return null;
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
