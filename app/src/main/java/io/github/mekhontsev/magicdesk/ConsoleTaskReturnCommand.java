package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ComponentName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class ConsoleTaskReturnCommand {
    private static final int PHONE_DISPLAY_ID = 0;
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private ConsoleTaskReturnCommand() {
    }

    public static void main(final String[] args) {
        final boolean selected = args.length >= 3
                && "selected".equals(args[0]);
        if (args.length != 1 && !selected) {
            System.err.println("usage: ConsoleTaskReturnCommand "
                    + "<console-display-id> | selected "
                    + "<console-display-id> <task-id>...");
            System.exit(64);
            return;
        }

        try {
            final int sourceDisplayId = parseDisplayId(
                    args[selected ? 1 : 0]);
            final Object service = HiddenTaskApi.getService();
            final List<Integer> taskIds = selected
                    ? findSelectedTasks(
                            service,
                            sourceDisplayId,
                            parseTaskIds(args, 2))
                    : findApplicationTasks(service, sourceDisplayId);
            Collections.reverse(taskIds);
            int moved = 0;
            int failed = 0;
            for (final int taskId : taskIds) {
                try {
                    TaskFullscreenMoveCommand.moveTask(
                            service,
                            taskId,
                            taskId,
                            sourceDisplayId,
                            PHONE_DISPLAY_ID);
                    System.out.println("task-returned=" + taskId);
                    moved++;
                } catch (ReflectiveOperationException | RuntimeException error) {
                    System.out.println("task-return-failed=" + taskId
                            + " error=" + usefulMessage(error));
                    failed++;
                }
            }
            System.out.println("tasks-returned=" + moved
                    + " failed=" + failed
                    + " from=" + sourceDisplayId
                    + " to=" + PHONE_DISPLAY_ID);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            System.err.println("task return failed: " + cause);
            System.exit(1);
        }
    }

    private static List<Integer> findSelectedTasks(
            final Object service,
            final int displayId,
            final Set<Integer> requestedTaskIds)
            throws ReflectiveOperationException {
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int taskId = HiddenTaskApi.getTaskId(task);
            if (getActivityType(task) == ACTIVITY_TYPE_STANDARD
                    && requestedTaskIds.contains(Integer.valueOf(taskId))) {
                taskIds.add(Integer.valueOf(taskId));
            }
        }
        return taskIds;
    }

    private static List<Integer> findApplicationTasks(final Object service,
            final int displayId) throws ReflectiveOperationException {
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task :
                HiddenTaskApi.getTasks(service, displayId)) {
            if (getActivityType(task) != ACTIVITY_TYPE_STANDARD
                    || isMagicDeskTask(task)) {
                continue;
            }
            taskIds.add(Integer.valueOf(
                    HiddenTaskApi.getTaskId(task)));
        }
        return taskIds;
    }

    private static boolean isMagicDeskTask(final Object task)
            throws ReflectiveOperationException {
        final ComponentName topActivity =
                HiddenTaskApi.getTaskTopActivity(task);
        if (isMagicDeskPackage(topActivity)) {
            return true;
        }
        final ComponentName baseActivity =
                HiddenTaskApi.getTaskBaseActivity(task);
        return isMagicDeskPackage(baseActivity);
    }

    private static boolean isMagicDeskPackage(final ComponentName component) {
        return component != null
                && component.getPackageName()
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".magicdesk");
    }

    private static int getActivityType(final Object task)
            throws ReflectiveOperationException {
        return HiddenTaskApi.getTaskActivityType(task);
    }

    private static int parseDisplayId(final String value) {
        final int displayId = Integer.parseInt(value);
        if (displayId <= 0) {
            throw new IllegalArgumentException("invalid Console display id");
        }
        return displayId;
    }

    private static Set<Integer> parseTaskIds(
            final String[] args, final int start) {
        final Set<Integer> taskIds = new LinkedHashSet<>();
        for (int index = start; index < args.length; index++) {
            final int taskId = Integer.parseInt(args[index]);
            if (taskId < 0) {
                throw new IllegalArgumentException("invalid task id");
            }
            taskIds.add(Integer.valueOf(taskId));
        }
        return taskIds;
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return (message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message)
                .replace('\n', ' ');
    }
}
