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
    private static final Pattern FRAME = Pattern.compile(
            "\\bframe=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    private TaskLocalInsetsSourceParser() {
    }

    static int findCaptionSourceId(final String dump, final int taskId) {
        final CaptionSource source = findCaptionSource(dump, taskId);
        return source == null ? NO_SOURCE_ID : source.sourceId;
    }

    static CaptionSource findCaptionSource(final String dump, final int taskId) {
        if (dump == null || dump.isEmpty() || taskId < 0) {
            return null;
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
                final int sourceId = parseSourceId(source.group(1));
                if (sourceId == NO_SOURCE_ID) {
                    return null;
                }
                final Matcher frame = FRAME.matcher(line);
                return new CaptionSource(
                        sourceId,
                        frame.find()
                                ? new Frame(
                                        parseCoordinate(frame.group(1)),
                                        parseCoordinate(frame.group(2)),
                                        parseCoordinate(frame.group(3)),
                                        parseCoordinate(frame.group(4)))
                                : null);
            }
        }
        return null;
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

    private static int parseCoordinate(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static final class CaptionSource {
        final int sourceId;
        final Frame frame;

        CaptionSource(final int sourceId, final Frame frame) {
            this.sourceId = sourceId;
            this.frame = frame;
        }
    }

    static final class Frame {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Frame(final int left, final int top, final int right, final int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        String shortString() {
            return "[" + left + "," + top + "]["
                    + right + "," + bottom + "]";
        }
    }
}
