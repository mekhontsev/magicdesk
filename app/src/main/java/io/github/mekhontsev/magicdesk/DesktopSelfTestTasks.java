package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;

import java.io.IOException;
import java.util.List;

/** Shared task-stack queries used by self-test suites and cleanup. */
final class DesktopSelfTestTasks {
    static final long STEP_TIMEOUT_MILLIS = 10_000L;
    static final long POLL_MILLIS = 100L;

    private DesktopSelfTestTasks() {
    }

    static TaskStackParser.Entry waitForTask(
            final int displayId,
            final String className,
            final TaskPredicate predicate) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        TaskStackParser.Entry lastObserved = null;
        do {
            final String stack = ShellAccess.run(
                    "/system/bin/cmd activity stack list");
            lastObserved = findTask(stack, displayId, className);
            final TaskStackParser.Entry task = findTask(
                    stack, displayId, className, predicate);
            if (task != null) {
                return task;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_APPEARANCE,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className
                + " did not reach the expected state on display " + displayId
                + "; last=" + describe(lastObserved));
    }

    static DesktopSelfTestTaskHierarchy.Snapshot waitForStableFullscreenTask(
            final int displayId,
            final int taskId,
            final int expectedFeatureId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        DesktopSelfTestTaskHierarchy.Snapshot lastObserved = null;
        do {
            lastObserved = DesktopSelfTestTaskHierarchy.inspect(
                    displayId, taskId);
            if (lastObserved != null
                    && lastObserved.displayId == displayId
                    && lastObserved.windowingMode == 1
                    && lastObserved.featureId == expectedFeatureId) {
                return lastObserved;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + taskId
                + " did not remain fullscreen in feature "
                + expectedFeatureId + " on display " + displayId
                + "; last=" + lastObserved);
    }

    private static String describe(final TaskStackParser.Entry task) {
        if (task == null) {
            return "absent";
        }
        return "task=" + task.taskId
                + "/display=" + task.displayId
                + "/mode=" + task.windowingMode
                + "/" + (task.visible ? "visible" : "hidden")
                + "/bounds=" + DesktopSelfTestGeometry.format(task.bounds);
    }

    static TaskStackParser.Entry waitForFrontTask(
            final int displayId,
            final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry front = findFrontTask(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    displayId);
            if (front != null && front.taskId == taskId) {
                return front;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.INPUT_FOCUS,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + taskId
                + " did not receive front focus on display " + displayId);
    }

    static TaskStackParser.Entry waitForDesktopHostFront(
            final int displayId,
            final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        TaskStackParser.Entry lastHost = null;
        TaskStackParser.Entry lastApp = null;
        do {
            final String stack = ShellAccess.run(
                    "/system/bin/cmd activity stack list");
            lastHost = findTaskById(stack, taskId);
            lastApp = findFrontTask(stack, displayId);
            if (lastHost != null
                    && lastHost.displayId == displayId
                    && lastHost.visible
                    && (lastHost.isHome()
                            ? lastApp == null
                            : lastApp != null
                                    && lastApp.taskId == taskId)) {
                return lastHost;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.INPUT_FOCUS,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("desktop host " + taskId
                + " did not become the front desktop task on display "
                + displayId + "; host=" + describe(lastHost)
                + ", front-app=" + describe(lastApp));
    }

    static String waitForReadyDesktopHost(
            final int displayId,
            final int taskId) throws IOException {
        // External drivers host DesktopActivity as Home, while the phone
        // session keeps it as a regular fullscreen task.
        waitForDesktopHostFront(displayId, taskId);
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        do {
            if (DesktopRuntimeBridge.isDesktopReadyOnDisplay(displayId)
                    && DesktopRuntimeBridge.isTaskbarVisibleOnDisplay(
                            displayId)) {
                return "host=" + taskId + ", taskbar=visible";
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_VISIBILITY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(
                "desktop host returned without a visible taskbar");
    }

    static String waitForDesktopHostAboveTasks(
            final int displayId,
            final int hostTaskId,
            final int... lowerTaskIds) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        String lastOrder = "unavailable";
        do {
            final List<TaskStackParser.Entry> tasks = TaskStackParser.parse(
                    ShellAccess.run("/system/bin/cmd activity stack list"));
            final int hostIndex = indexOfTask(
                    tasks, displayId, hostTaskId);
            boolean ordered = hostIndex >= 0 && tasks.get(hostIndex).visible;
            final StringBuilder order = new StringBuilder()
                    .append("host=").append(hostTaskId)
                    .append('@').append(hostIndex);
            if (lowerTaskIds != null) {
                for (final int lowerTaskId : lowerTaskIds) {
                    final int lowerIndex = indexOfTask(
                            tasks, displayId, lowerTaskId);
                    order.append(", task=").append(lowerTaskId)
                            .append('@').append(lowerIndex);
                    ordered &= lowerIndex > hostIndex;
                }
            }
            lastOrder = order.toString();
            if (ordered
                    && DesktopRuntimeBridge.isDesktopReadyOnDisplay(displayId)
                    && DesktopRuntimeBridge.isTaskbarVisibleOnDisplay(
                            displayId)) {
                return lastOrder + ", taskbar=visible";
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("desktop host did not settle above concealed "
                + "tasks on display " + displayId + "; " + lastOrder);
    }

    private static int indexOfTask(
            final List<TaskStackParser.Entry> tasks,
            final int displayId,
            final int taskId) {
        for (int index = 0; index < tasks.size(); index++) {
            final TaskStackParser.Entry task = tasks.get(index);
            if (task.displayId == displayId && task.taskId == taskId) {
                return index;
            }
        }
        return -1;
    }

    static void sendSystemBack(final int displayId) throws IOException {
        ShellAccess.run("/system/bin/input -d " + displayId
                + " keyevent KEYCODE_BACK");
    }

    static DesktopTaskLaunchProbe.Observation launchWindowedAndObserve(
            final int displayId,
            final Rect bounds,
            final String fixtureClass,
            final Intent launchIntent) throws IOException {
        final ComponentName component = launchIntent == null
                ? null : launchIntent.getComponent();
        if (component == null) {
            throw new IOException("test window component is unavailable");
        }
        if (!hasClass(component.flattenToShortString(), fixtureClass)) {
            throw new IOException("unexpected test window component: "
                    + component.flattenToShortString());
        }
        try (DesktopTaskLaunchProbe probe =
                     DesktopTaskLaunchProbe.open(-1, component)) {
            final int launchedTaskId = MagicDeskRuntime.launchWindowedTask(
                    displayId, launchIntent, bounds);
            final DesktopTaskLaunchProbe.Observation observation =
                    probe.awaitObservation();
            if (observation.displayId != displayId
                    || (launchedTaskId >= 0
                            && observation.taskId != launchedTaskId)) {
                throw new IOException(
                        "test window launched on the wrong display: "
                                + observation);
            }
            return observation;
        }
    }

    static void waitForTaskAbsent(final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            if (findTaskById(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    taskId) == null) {
                return;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_VISIBILITY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + taskId + " remained after close");
    }

    static TaskStackParser.Entry findTaskById(
            final String stack,
            final int taskId) {
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (task.taskId == taskId) {
                return task;
            }
        }
        return null;
    }

    static TaskStackParser.Entry findFrontTask(
            final String stack,
            final int displayId) {
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (task.displayId == displayId
                    && task.visible
                    && !task.isHome()
                    && !isInfrastructureTask(task)) {
                return task;
            }
        }
        return null;
    }

    private static boolean isInfrastructureTask(
            final TaskStackParser.Entry task) {
        final String backstopClassName = BuildConfig.APPLICATION_ID
                + ".TaskAreaBackstopActivity";
        return hasClass(task.componentName, backstopClassName)
                || hasClass(task.topActivityName, backstopClassName)
                || DesktopTaskbarActivity.isTaskbarComponentName(
                        task.componentName)
                || DesktopTaskbarActivity.isTaskbarComponentName(
                        task.topActivityName);
    }

    static TaskStackParser.Entry findTask(
            final String stack,
            final int displayId,
            final String className) {
        return findTask(stack, displayId, className, null);
    }

    static TaskStackParser.Entry findTask(
            final String stack,
            final int displayId,
            final String className,
            final TaskPredicate predicate) {
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (task.displayId == displayId
                    && (hasClass(task.componentName, className)
                            || hasClass(task.topActivityName, className))
                    && (predicate == null || predicate.test(task))) {
                return task;
            }
        }
        return null;
    }

    static TaskStackParser.Entry findTaskOnAnyDisplay(
            final String stack,
            final String className) {
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (hasClass(task.componentName, className)
                    || hasClass(task.topActivityName, className)) {
                return task;
            }
        }
        return null;
    }

    static boolean hasClass(
            final String component,
            final String className) {
        if (component == null || className == null) {
            return false;
        }
        final int separator = component.indexOf('/');
        if (separator < 0 || separator + 1 >= component.length()) {
            return false;
        }
        final String activity = component.substring(separator + 1);
        if (className.equals(activity)) {
            return true;
        }
        return activity.startsWith(".")
                && className.equals(
                        component.substring(0, separator) + activity);
    }

    interface TaskPredicate {
        boolean test(TaskStackParser.Entry task);
    }
}
