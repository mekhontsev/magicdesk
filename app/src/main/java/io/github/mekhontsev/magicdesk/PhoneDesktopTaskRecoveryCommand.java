package io.github.mekhontsev.magicdesk;

import android.os.Bundle;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Revives Recent tasks needed to reconcile WMShell's phone desktop state. */
public final class PhoneDesktopTaskRecoveryCommand {
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";

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
            int revived = 0;
            int live = 0;
            int unresolved = 0;
            int rejected = 0;
            for (final Integer candidate : candidates) {
                if (liveTasks.containsKey(candidate)) {
                    live++;
                    continue;
                }
                final Object recentTask = recentTasks.get(candidate);
                if (recentTask == null) {
                    unresolved++;
                    continue;
                }
                final String packageName =
                        HiddenTaskApi.getTaskPackage(recentTask);
                if (!PackageNameValidator.isSafe(packageName)
                        || MAGICDESK_PACKAGE.equals(packageName)) {
                    rejected++;
                    continue;
                }
                startTaskFromRecents(service, candidate.intValue());
                revived++;
            }
            System.out.println("phone-desktop-recovery candidates="
                    + candidates.size()
                    + " revived=" + revived
                    + " live=" + live
                    + " unresolved=" + unresolved
                    + " rejected=" + rejected);
            if (unresolved != 0 || rejected != 0) {
                System.exit(1);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("phone desktop recovery failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    private static void startTaskFromRecents(
            final Object service,
            final int taskId) throws ReflectiveOperationException {
        final Object result = service.getClass().getMethod(
                "startActivityFromRecents", Integer.TYPE, Bundle.class)
                .invoke(service, Integer.valueOf(taskId), null);
        if (!(result instanceof Integer)
                || ((Integer) result).intValue() < 0) {
            throw new IllegalStateException(
                    "could not revive task " + taskId + ": " + result);
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
