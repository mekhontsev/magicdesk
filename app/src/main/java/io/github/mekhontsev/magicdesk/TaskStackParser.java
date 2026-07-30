package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TaskStackParser {
    private static final Pattern ROOT_TASK_PATTERN =
            Pattern.compile("RootTask id=(\\d+).* displayId=(\\d+)");
    private static final Pattern WINDOWING_MODE_PATTERN =
            Pattern.compile("mWindowingMode=([^\\s}]+)");
    private static final Pattern ACTIVITY_TYPE_PATTERN =
            Pattern.compile("mActivityType=([^\\s}]+)");
    private static final Pattern TOP_ACTIVITY_PATTERN =
            Pattern.compile("topActivity=ComponentInfo\\{([^}]+)\\}");
    private static final Pattern BOUNDS_PATTERN = Pattern.compile(
            "bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    private TaskStackParser() {
    }

    static List<Entry> parse(final String output) {
        final List<Entry> tasks = new ArrayList<>();
        int rootTaskId = -1;
        int displayId = -1;
        String windowingMode = null;
        String activityType = null;
        Bounds rootBounds = null;

        for (final String line : output.split("\\r?\\n")) {
            final Matcher rootMatcher = ROOT_TASK_PATTERN.matcher(line);
            if (rootMatcher.find()) {
                rootTaskId = parseInt(rootMatcher.group(1));
                displayId = parseInt(rootMatcher.group(2));
                windowingMode = null;
                activityType = null;
                rootBounds = parseBounds(line);
                continue;
            }
            if (rootTaskId < 0) {
                continue;
            }
            if (line.contains("configuration=")) {
                final Matcher modeMatcher =
                        WINDOWING_MODE_PATTERN.matcher(line);
                if (modeMatcher.find()) {
                    windowingMode = modeMatcher.group(1);
                }
                final Matcher typeMatcher =
                        ACTIVITY_TYPE_PATTERN.matcher(line);
                if (typeMatcher.find()) {
                    activityType = typeMatcher.group(1);
                }
                continue;
            }

            final String trimmed = line.trim();
            if (!trimmed.startsWith("taskId=")
                    || !trimmed.contains(" topActivity=ComponentInfo{")) {
                continue;
            }
            final int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final int taskId = parseInt(
                    trimmed.substring("taskId=".length(), colon));
            final String component = readComponent(trimmed, colon + 1);
            if (taskId < 0 || component == null
                    || "unknown".equals(component)
                    || component.indexOf('/') <= 0) {
                continue;
            }
            final String packageName =
                    component.substring(0, component.indexOf('/'));
            if (!PackageNameValidator.isSafe(packageName)) {
                continue;
            }
            final Matcher topActivityMatcher =
                    TOP_ACTIVITY_PATTERN.matcher(trimmed);
            final String topActivityName = topActivityMatcher.find()
                    ? topActivityMatcher.group(1) : component;
            final Bounds taskBounds = parseBounds(trimmed);
            tasks.add(new Entry(
                    rootTaskId,
                    taskId,
                    displayId,
                    packageName,
                    component,
                    topActivityName,
                    windowingMode,
                    activityType,
                    taskBounds == null ? rootBounds : taskBounds,
                    trimmed.contains(" visible=true")));
        }
        return tasks;
    }

    private static String readComponent(
            final String line,
            final int start) {
        int componentStart = start;
        while (componentStart < line.length()
                && Character.isWhitespace(line.charAt(componentStart))) {
            componentStart++;
        }
        int componentEnd = componentStart;
        while (componentEnd < line.length()
                && !Character.isWhitespace(line.charAt(componentEnd))) {
            componentEnd++;
        }
        return componentEnd > componentStart
                ? line.substring(componentStart, componentEnd)
                : null;
    }

    private static int parseInt(final String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static Bounds parseBounds(final String value) {
        final Matcher matcher = BOUNDS_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        final Bounds bounds = new Bounds(
                parseInt(matcher.group(1)),
                parseInt(matcher.group(2)),
                parseInt(matcher.group(3)),
                parseInt(matcher.group(4)));
        return bounds.isEmpty() ? null : bounds;
    }

    static final class Entry {
        final int rootTaskId;
        final int taskId;
        final int displayId;
        final String packageName;
        final String componentName;
        final String topActivityName;
        final String windowingMode;
        final String activityType;
        final Bounds bounds;
        final boolean visible;

        Entry(
                final int rootTaskId,
                final int taskId,
                final int displayId,
                final String packageName,
                final String componentName,
                final String topActivityName,
                final String windowingMode,
                final String activityType,
                final Bounds bounds,
                final boolean visible) {
            this.rootTaskId = rootTaskId;
            this.taskId = taskId;
            this.displayId = displayId;
            this.packageName = packageName;
            this.componentName = componentName;
            this.topActivityName = topActivityName;
            this.windowingMode = windowingMode;
            this.activityType = activityType;
            this.bounds = bounds == null ? Bounds.EMPTY : bounds;
            this.visible = visible;
        }

        boolean isHome() {
            return "home".equals(activityType);
        }
    }

    static final class Bounds {
        static final Bounds EMPTY = new Bounds(0, 0, 0, 0);

        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(
                final int left,
                final int top,
                final int right,
                final int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean isEmpty() {
            return left >= right || top >= bottom;
        }
    }
}
