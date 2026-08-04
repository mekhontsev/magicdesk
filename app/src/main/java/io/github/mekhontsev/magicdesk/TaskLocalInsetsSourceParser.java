package io.github.mekhontsev.magicdesk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts task-local inset source IDs from a WindowManager text dump. */
final class TaskLocalInsetsSourceParser {
    static final int NO_SOURCE_ID = -1;

    private static final Pattern TASK = Pattern.compile(
            "^\\s*\\* Task\\{.*#(\\d+)\\b.*$");
    private static final Pattern CAPTION_SOURCE = Pattern.compile(
            "\\bInsetsSource id=([0-9a-fA-F]+) type=captionBar\\b");

    private TaskLocalInsetsSourceParser() {
    }

    static int findCaptionSourceId(final String dump, final int taskId) {
        if (dump == null || dump.isEmpty() || taskId < 0) {
            return NO_SOURCE_ID;
        }
        boolean targetTask = false;
        for (final String line : dump.split("\\R")) {
            final Matcher task = TASK.matcher(line);
            if (task.matches()) {
                targetTask = parseTaskId(task.group(1)) == taskId;
                continue;
            }
            if (!targetTask) {
                continue;
            }
            final Matcher source = CAPTION_SOURCE.matcher(line);
            if (source.find()) {
                return parseSourceId(source.group(1));
            }
        }
        return NO_SOURCE_ID;
    }

    private static int parseTaskId(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int parseSourceId(final String value) {
        try {
            final long sourceId = Long.parseLong(value, 16);
            return sourceId <= 0xffffffffL
                    ? (int) sourceId : NO_SOURCE_ID;
        } catch (NumberFormatException ignored) {
            return NO_SOURCE_ID;
        }
    }
}
