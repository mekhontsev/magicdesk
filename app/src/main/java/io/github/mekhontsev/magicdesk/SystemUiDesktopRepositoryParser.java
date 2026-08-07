package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashSet;
import java.util.Set;

/** Parses the task IDs retained by WMShell's desktop repository. */
final class SystemUiDesktopRepositoryParser {
    private static final String REPOSITORIES = "DesktopUserRepositories:";
    private static final String REPOSITORY = "DesktopRepository";
    private static final String CURRENT_USER = "currentUserId=";
    private static final String USER = "userId=";
    private static final String DISPLAY = "Display #";
    private static final String[] TASK_LISTS = {
            "activeTasks=",
            "visibleTasks=",
            "freeformTasksInZOrder=",
            "minimizedTasks="
    };

    private SystemUiDesktopRepositoryParser() {
    }

    static Set<Integer> parseTaskIds(
            final String output,
            final int targetDisplayId) {
        final Set<Integer> taskIds = new LinkedHashSet<>();
        if (output == null || output.isEmpty()) {
            return taskIds;
        }

        boolean inRepositories = false;
        boolean inCurrentUserRepository = false;
        boolean inTargetDisplay = false;
        int currentUserId = -1;
        for (final String rawLine : output.split("\\R")) {
            final String line = rawLine.trim();
            if (REPOSITORIES.equals(line)) {
                inRepositories = true;
                inCurrentUserRepository = false;
                inTargetDisplay = false;
                continue;
            }
            if (!inRepositories || line.isEmpty()) {
                continue;
            }
            if (line.startsWith(CURRENT_USER)) {
                currentUserId = parseInteger(line.substring(CURRENT_USER.length()));
                continue;
            }
            if (REPOSITORY.equals(line)) {
                inCurrentUserRepository = false;
                inTargetDisplay = false;
                continue;
            }
            if (line.startsWith(USER)) {
                final int repositoryUserId =
                        parseInteger(line.substring(USER.length()));
                inCurrentUserRepository = currentUserId >= 0
                        && repositoryUserId == currentUserId;
                inTargetDisplay = false;
                continue;
            }
            if (line.startsWith(DISPLAY)) {
                final int separator = line.indexOf(':', DISPLAY.length());
                final int displayId = parseInteger(line.substring(
                        DISPLAY.length(), separator < 0 ? line.length() : separator));
                inTargetDisplay = inCurrentUserRepository
                        && displayId == targetDisplayId;
                continue;
            }
            if (!inTargetDisplay) {
                continue;
            }
            for (final String prefix : TASK_LISTS) {
                if (line.startsWith(prefix)) {
                    addTaskIds(line.substring(prefix.length()), taskIds);
                    break;
                }
            }
        }
        return taskIds;
    }

    private static void addTaskIds(
            final String value,
            final Set<Integer> taskIds) {
        final int start = value.indexOf('[');
        final int end = value.indexOf(']', start + 1);
        if (start < 0 || end < 0) {
            return;
        }
        final String contents = value.substring(start + 1, end).trim();
        if (contents.isEmpty()) {
            return;
        }
        for (final String token : contents.split(",")) {
            final int taskId = parseInteger(token.trim());
            if (taskId >= 0) {
                taskIds.add(Integer.valueOf(taskId));
            }
        }
    }

    private static int parseInteger(final String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
