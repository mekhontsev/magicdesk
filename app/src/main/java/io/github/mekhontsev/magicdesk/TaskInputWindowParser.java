package io.github.mekhontsev.magicdesk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads native caption and resize input windows from {@code dumpsys input}. */
final class TaskInputWindowParser {
    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "\\[(-?\\d+),\\s*(-?\\d+)]\\[(-?\\d+),\\s*(-?\\d+)]");

    private TaskInputWindowParser() {
    }

    static Entry findCaption(final String dump, final int taskId) {
        return find(dump, "name=Embedded{Caption of Task=" + taskId + "},");
    }

    static Entry findResize(final String dump, final int taskId) {
        return find(dump,
                "name=Embedded{DragResizeInputListener of Surface(name="
                        + "Decor container of Task=" + taskId + ")/");
    }

    private static Entry find(final String dump, final String marker) {
        if (dump == null || dump.isEmpty()) {
            return null;
        }
        for (final String rawLine : dump.split("\\r?\\n")) {
            final int markerIndex = rawLine.indexOf(marker);
            if (markerIndex < 0) {
                continue;
            }
            final Integer displayId = integerField(rawLine, "displayId=", ",");
            final Frame frame = frameField(rawLine);
            if (displayId == null || frame == null) {
                return null;
            }
            return new Entry(
                    displayId.intValue(),
                    frame,
                    field(rawLine, "inputConfig=", ", alpha="),
                    field(rawLine, "touchableRegion=", ", ownerPid="),
                    field(rawLine, ", token=", ", touchOcclusionMode="));
        }
        return null;
    }

    private static Frame frameField(final String line) {
        final String value = field(line, "frame=", ", globalScale=");
        if (value.isEmpty()) {
            return null;
        }
        final Matcher matcher = FRAME_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new Frame(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Integer integerField(
            final String line, final String prefix, final String suffix) {
        final String value = field(line, prefix, suffix);
        try {
            return value.isEmpty() ? null : Integer.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String field(
            final String line, final String prefix, final String suffix) {
        final int start = line.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        final int valueStart = start + prefix.length();
        final int end = line.indexOf(suffix, valueStart);
        if (end < valueStart) {
            return "";
        }
        return line.substring(valueStart, end).trim();
    }

    static final class Entry {
        final int displayId;
        final Frame frame;
        final String inputConfig;
        final String touchableRegion;
        final String token;

        Entry(
                final int displayId,
                final Frame frame,
                final String inputConfig,
                final String touchableRegion,
                final String token) {
            this.displayId = displayId;
            this.frame = frame;
            this.inputConfig = inputConfig;
            this.touchableRegion = touchableRegion;
            this.token = token;
        }

        boolean hasConfig(final String name) {
            return inputConfig != null && inputConfig.contains(name);
        }

        boolean hasInputChannel() {
            return !hasConfig("NO_INPUT_CHANNEL")
                    && token != null
                    && !token.isEmpty()
                    && !"0x0".equals(token);
        }

        boolean hasTouchableRegion() {
            return touchableRegion != null
                    && !touchableRegion.isEmpty()
                    && !"<empty>".equals(touchableRegion);
        }
    }

    static final class Frame {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Frame(
                final int left,
                final int top,
                final int right,
                final int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @Override
        public String toString() {
            return "[" + left + "," + top + "]["
                    + right + "," + bottom + "]";
        }
    }
}
