package io.github.mekhontsev.magicdesk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads task-related input state from {@code dumpsys input}. */
final class TaskInputWindowParser {
    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "\\[(-?\\d+),\\s*(-?\\d+)]\\[(-?\\d+),\\s*(-?\\d+)]");
    private static final Pattern FOCUSED_APPLICATION_PATTERN = Pattern.compile(
            "(?m)^\\s*displayId=(\\d+), name='ActivityRecord\\{[^\\n]*\\st(\\d+)\\}'");
    private static final Pattern FOCUSED_WINDOW_PATTERN = Pattern.compile(
            "(?m)^\\s*displayId=(\\d+), name='([^'\\s]+)(?:\\s[^']*)?'");
    private static final Pattern WINDOW_TASK_PATTERN = Pattern.compile(
            "applicationInfo\\.name=ActivityRecord\\{[^\\n]*\\st(\\d+)(?:\\s|\\})");

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

    static Entry findMaximizeMenu(final String dump, final int taskId) {
        return find(dump, "Maximize Menu for Task=" + taskId);
    }

    static boolean isTaskFocused(
            final String dump, final int displayId, final int taskId) {
        final String dispatcher = currentDispatcherState(dump);
        if (dispatcher.isEmpty()) {
            return false;
        }
        final String applications = section(
                dispatcher, "FocusedApplications:", "FocusedWindows:");
        final Matcher applicationMatcher =
                FOCUSED_APPLICATION_PATTERN.matcher(applications);
        boolean focusedApplication = false;
        while (applicationMatcher.find()) {
            if (Integer.parseInt(applicationMatcher.group(1)) == displayId
                    && Integer.parseInt(applicationMatcher.group(2)) == taskId) {
                focusedApplication = true;
                break;
            }
        }
        final String windows = section(
                dispatcher, "FocusedWindows:", "FocusRequests:");
        final int windowTaskId = findFocusedWindowTaskId(
                dispatcher, windows, displayId);
        if (windowTaskId >= 0) {
            return windowTaskId == taskId;
        }
        return hasFocusedWindow(windows, displayId) && focusedApplication;
    }

    static int findFocusedTaskId(final String dump, final int displayId) {
        final String dispatcher = currentDispatcherState(dump);
        if (dispatcher.isEmpty()) {
            return -1;
        }
        return findFocusedWindowTaskId(
                dispatcher,
                section(dispatcher, "FocusedWindows:", "FocusRequests:"),
                displayId);
    }

    private static int findFocusedWindowTaskId(
            final String dispatcher,
            final String windows,
            final int displayId) {
        final Matcher windowMatcher = FOCUSED_WINDOW_PATTERN.matcher(windows);
        while (windowMatcher.find()) {
            if (Integer.parseInt(windowMatcher.group(1)) != displayId) {
                continue;
            }
            final Integer taskId = findWindowTaskId(
                    dispatcher, displayId, windowMatcher.group(2));
            return taskId == null ? -1 : taskId.intValue();
        }
        return -1;
    }

    private static boolean hasFocusedWindow(
            final String windows,
            final int displayId) {
        final Matcher matcher = FOCUSED_WINDOW_PATTERN.matcher(windows);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) == displayId) {
                return true;
            }
        }
        return false;
    }

    private static Integer findWindowTaskId(
            final String dispatcher,
            final int displayId,
            final String windowId) {
        if (windowId == null || windowId.isEmpty()) {
            return null;
        }
        final String nameWithTitle = "name=" + windowId + " ";
        final String nameWithoutTitle = "name=" + windowId + ",";
        final String displayMarker = "displayId=" + displayId + ",";
        for (final String line : dispatcher.split("\\r?\\n")) {
            if ((!line.contains(nameWithTitle)
                            && !line.contains(nameWithoutTitle))
                    || !line.contains(displayMarker)
                    || !line.contains("applicationInfo.name=ActivityRecord{")) {
                continue;
            }
            final Matcher taskMatcher = WINDOW_TASK_PATTERN.matcher(line);
            if (taskMatcher.find()) {
                try {
                    return Integer.valueOf(taskMatcher.group(1));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String currentDispatcherState(final String dump) {
        if (dump == null || dump.isEmpty()) {
            return "";
        }
        final String marker = "Input Dispatcher State:";
        final int start = dump.indexOf(marker);
        if (start < 0) {
            return "";
        }
        final int staleState = dump.indexOf(
                "Input Dispatcher State at time of last ANR:", start);
        return staleState < 0
                ? dump.substring(start) : dump.substring(start, staleState);
    }

    private static String section(
            final String text, final String startMarker, final String endMarker) {
        final int start = text.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        final int contentStart = start + startMarker.length();
        final int end = text.indexOf(endMarker, contentStart);
        return end < 0
                ? text.substring(contentStart)
                : text.substring(contentStart, end);
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
            final Entry entry = new Entry(
                    displayId.intValue(),
                    frame,
                    field(rawLine, "inputConfig=", ", alpha="),
                    field(rawLine, "touchableRegion=", ", ownerPid="),
                    field(rawLine, ", token=", ", touchOcclusionMode="));
            if (!entry.hasConfig("CLONE")) {
                return entry;
            }
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
