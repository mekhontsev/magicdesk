package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;

import java.io.IOException;

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
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className
                + " did not reach the expected state on display " + displayId
                + "; last=" + describe(lastObserved));
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
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + taskId
                + " did not receive front focus on display " + displayId);
    }

    static void waitForTaskAbsent(final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            if (findTaskById(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    taskId) == null) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
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
                    && !task.isHome()) {
                return task;
            }
        }
        return null;
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
