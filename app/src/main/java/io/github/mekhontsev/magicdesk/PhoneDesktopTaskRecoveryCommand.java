package io.github.mekhontsev.magicdesk;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Removes dead Recent entries still retained by WMShell's phone desktop. */
public final class PhoneDesktopTaskRecoveryCommand {
    private PhoneDesktopTaskRecoveryCommand() {
    }

    public static void main(final String[] args) {
        final Set<Integer> candidates = parseCandidates(args);
        if (candidates == null) {
            System.err.println("usage: PhoneDesktopTaskRecoveryCommand <task-id>...");
            System.exit(64);
            return;
        }

        try {
            final Object service = HiddenTaskApi.getService();
            final Map<Integer, Object> liveTasks = indexTasks(
                    HiddenTaskApi.getAllTasks(service));
            final Map<Integer, Object> recentTasks = indexTasks(
                    HiddenTaskApi.getRecentTasks(service));
            int removed = 0;
            int live = 0;
            int absent = 0;
            int rejected = 0;
            for (final Integer candidate : candidates) {
                if (liveTasks.containsKey(candidate)) {
                    live++;
                    continue;
                }
                final Object recentTask = recentTasks.get(candidate);
                if (recentTask == null) {
                    absent++;
                    continue;
                }
                final String packageName =
                        HiddenTaskApi.getTaskPackage(recentTask);
                if (!PackageNameValidator.isSafe(packageName)) {
                    rejected++;
                    continue;
                }
                if (TaskControlCommand.removeTask(
                        service, candidate.intValue())) {
                    removed++;
                } else {
                    throw new IllegalStateException(
                            "could not remove orphaned task " + candidate);
                }
            }
            System.out.println("phone-desktop-recovery candidates="
                    + candidates.size()
                    + " removed=" + removed
                    + " live=" + live
                    + " absent=" + absent
                    + " rejected=" + rejected);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("phone desktop recovery failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    private static Set<Integer> parseCandidates(final String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        final Set<Integer> result = new LinkedHashSet<>();
        try {
            for (final String argument : args) {
                final int taskId = Integer.parseInt(argument);
                if (taskId < 0) {
                    return null;
                }
                result.add(Integer.valueOf(taskId));
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return result;
    }

    private static Map<Integer, Object> indexTasks(final List<?> tasks)
            throws ReflectiveOperationException {
        final Map<Integer, Object> result = new HashMap<>();
        for (final Object task : tasks) {
            result.put(Integer.valueOf(
                    HiddenTaskApi.getIntField(task, "taskId")), task);
        }
        return result;
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
