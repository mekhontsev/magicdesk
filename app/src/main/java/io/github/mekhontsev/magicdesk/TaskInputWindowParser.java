package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final Pattern FOCUSED_WINDOW_NAME_PATTERN = Pattern.compile(
            "(?m)^\\s*displayId=(\\d+), name='([^']+)'$");
    private static final Pattern WINDOW_TASK_PATTERN = Pattern.compile(
            "applicationInfo\\.name=ActivityRecord\\{[^\\n]*\\st(\\d+)(?:\\s|\\})");
    private static final Pattern WINDOW_COMPONENT_PATTERN = Pattern.compile(
            "applicationInfo\\.name=ActivityRecord\\{[^\\n]*?\\su\\d+\\s+"
                    + "([^\\s/]+)/[^\\s]+\\st(\\d+)(?:\\s|\\})");
    private static final String CRASH_DIALOG_PREFIX = "Application Error: ";
    private static final String ANR_DIALOG_PREFIX =
            "Application Not Responding: ";

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
        final boolean focusedApplication =
                findFocusedApplicationTaskId(applications, displayId)
                        == taskId;
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

    static String describeFocus(final String dump, final int displayId) {
        final String dispatcher = currentDispatcherState(dump);
        if (dispatcher.isEmpty()) {
            return "dispatcher=missing, bytes="
                    + (dump == null ? 0 : dump.length());
        }
        final String applications = section(
                dispatcher, "FocusedApplications:", "FocusedWindows:");
        final int applicationTaskId =
                findFocusedApplicationTaskId(applications, displayId);
        final String windows = section(
                dispatcher, "FocusedWindows:", "FocusRequests:");
        return "applicationTask=" + applicationTaskId
                + ", windowTask=" + findFocusedWindowTaskId(
                        dispatcher, windows, displayId)
                + ", focusedWindow=" + hasFocusedWindow(windows, displayId)
                + ", bytes=" + (dump == null ? 0 : dump.length())
                + ", truncated=" + (dump != null && dump.contains(
                        "[MagicDesk: command output truncated]"));
    }

    static WindowSnapshot readWindowSnapshot(final String dump) {
        if (dump != null && dump.contains(
                "[MagicDesk: command output truncated]")) {
            return WindowSnapshot.unavailable();
        }
        final String dispatcher = currentDispatcherState(dump);
        if (dispatcher.isEmpty()) {
            return WindowSnapshot.unavailable();
        }
        final Map<String, WindowState> windows = new LinkedHashMap<>();
        final List<WindowState> taskWindows = new ArrayList<>();
        final List<WindowState> systemDialogs = new ArrayList<>();
        for (final String line : dispatcher.split("\\r?\\n")) {
            final WindowState window = parseWindowState(line);
            if (window == null) {
                continue;
            }
            windows.put(windowKey(window.displayId, window.windowId), window);
            if (window.taskId >= 0
                    && !window.notVisible
                    && !window.cloned) {
                taskWindows.add(window);
            }
            if (window.isErrorDialog()
                    && !window.notVisible
                    && !window.cloned) {
                systemDialogs.add(window);
            }
        }

        final Map<Integer, FocusedWindow> focused = new LinkedHashMap<>();
        final String focusedWindows = section(
                dispatcher, "FocusedWindows:", "FocusRequests:");
        final Matcher matcher =
                FOCUSED_WINDOW_NAME_PATTERN.matcher(focusedWindows);
        while (matcher.find()) {
            final int displayId = parseInteger(matcher.group(1), -1);
            final Name name = splitWindowName(matcher.group(2));
            if (displayId < 0 || name.windowId.isEmpty()) {
                continue;
            }
            final WindowState mapped = windows.get(
                    windowKey(displayId, name.windowId));
            final int applicationTaskId = findFocusedApplicationTaskId(
                    section(dispatcher,
                            "FocusedApplications:", "FocusedWindows:"),
                    displayId);
            focused.put(Integer.valueOf(displayId), new FocusedWindow(
                    displayId,
                    name.windowId,
                    mapped == null ? name.title : mapped.title,
                    mapped == null ? -1 : mapped.taskId,
                    applicationTaskId,
                    mapped == null ? -1 : mapped.ownerPid,
                    mapped == null ? -1 : mapped.ownerUid,
                    mapped == null
                            ? packageFromTitle(name.title)
                            : mapped.packageName,
                    kindFor(mapped == null ? name.title : mapped.title,
                            mapped == null ? -1 : mapped.taskId,
                            mapped == null ? -1 : mapped.ownerUid)));
        }
        for (final FocusedWindow window : focused.values()) {
            if (window.isSystemDialog() && !containsWindow(
                    systemDialogs, window.displayId, window.windowId)) {
                systemDialogs.add(window);
            }
        }
        return new WindowSnapshot(focused, taskWindows, systemDialogs, true);
    }

    static boolean hasVisibleNotificationPanel(
            final String dump, final int displayId) {
        final String dispatcher = currentDispatcherState(dump);
        if (dispatcher.isEmpty()) {
            return false;
        }
        for (final String line : dispatcher.split("\\r?\\n")) {
            final WindowState window = parseWindowState(line);
            if (window == null
                    || window.displayId != displayId
                    || window.notVisible) {
                continue;
            }
            final String title = window.title;
            if (title.contains("NotificationShade")
                    || title.contains("QuickSettings")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWindow(
            final List<WindowState> windows,
            final int displayId,
            final String windowId) {
        for (final WindowState window : windows) {
            if (window.displayId == displayId
                    && window.windowId.equals(windowId)) {
                return true;
            }
        }
        return false;
    }

    private static WindowState parseWindowState(final String line) {
        final int nameStart = line.indexOf("name=");
        final int nameEnd = line.indexOf(", id=", nameStart + 5);
        if (nameStart < 0 || nameEnd < 0 || !line.contains("displayId=")) {
            return null;
        }
        final Name name = splitWindowName(
                line.substring(nameStart + 5, nameEnd).trim());
        final Integer displayId = integerField(line, "displayId=", ",");
        if (displayId == null || name.windowId.isEmpty()) {
            return null;
        }
        final Matcher componentMatcher = WINDOW_COMPONENT_PATTERN.matcher(line);
        final int taskId;
        final String packageName;
        if (componentMatcher.find()) {
            packageName = componentMatcher.group(1);
            taskId = parseInteger(componentMatcher.group(2), -1);
        } else {
            final Matcher taskMatcher = WINDOW_TASK_PATTERN.matcher(line);
            taskId = taskMatcher.find()
                    ? parseInteger(taskMatcher.group(1), -1) : -1;
            packageName = packageFromTitle(name.title);
        }
        final int ownerPid = integerValue(line, "ownerPid=", ",");
        final int ownerUid = integerValue(line, "ownerUid=", ",");
        final String inputConfig = field(line, "inputConfig=", ", alpha=");
        return new WindowState(
                displayId.intValue(),
                name.windowId,
                name.title,
                taskId,
                ownerPid,
                ownerUid,
                packageName,
                kindFor(name.title, taskId, ownerUid),
                inputConfig.contains("NOT_VISIBLE"),
                inputConfig.contains("CLONE"));
    }

    private static int integerValue(
            final String line, final String prefix, final String suffix) {
        final Integer value = integerField(line, prefix, suffix);
        return value == null ? -1 : value.intValue();
    }

    private static int parseInteger(final String value, final int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static Name splitWindowName(final String value) {
        if (value == null) {
            return new Name("", "");
        }
        final String normalized = value.trim();
        final int separator = normalized.indexOf(' ');
        return separator < 0
                ? new Name(normalized, "")
                : new Name(
                        normalized.substring(0, separator),
                        normalized.substring(separator + 1).trim());
    }

    private static String packageFromTitle(final String title) {
        if (title == null) {
            return "";
        }
        final String normalized = title.trim();
        if (normalized.startsWith(CRASH_DIALOG_PREFIX)) {
            return normalized.substring(CRASH_DIALOG_PREFIX.length()).trim();
        }
        if (normalized.startsWith(ANR_DIALOG_PREFIX)) {
            return normalized.substring(ANR_DIALOG_PREFIX.length()).trim();
        }
        final int slash = normalized.indexOf('/');
        if (slash > 0) {
            return normalized.substring(0, slash);
        }
        return "";
    }

    private static String kindFor(
            final String title, final int taskId, final int ownerUid) {
        if (title != null && title.startsWith(CRASH_DIALOG_PREFIX)) {
            return WindowState.KIND_CRASH_DIALOG;
        }
        if (title != null && title.startsWith(ANR_DIALOG_PREFIX)) {
            return WindowState.KIND_ANR_DIALOG;
        }
        if (taskId >= 0) {
            return WindowState.KIND_APPLICATION;
        }
        return ownerUid == android.os.Process.SYSTEM_UID
                ? WindowState.KIND_SYSTEM_DIALOG
                : WindowState.KIND_UNKNOWN;
    }

    private static String windowKey(
            final int displayId, final String windowId) {
        return displayId + ":" + windowId;
    }

    private static int findFocusedApplicationTaskId(
            final String applications,
            final int displayId) {
        final Matcher matcher =
                FOCUSED_APPLICATION_PATTERN.matcher(applications);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) == displayId) {
                return Integer.parseInt(matcher.group(2));
            }
        }
        return -1;
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

    static final class WindowSnapshot {
        private final Map<Integer, FocusedWindow> mFocused;
        private final List<WindowState> mTaskWindows;
        private final List<WindowState> mSystemDialogs;
        final boolean available;

        WindowSnapshot(
                final Map<Integer, FocusedWindow> focused,
                final List<WindowState> taskWindows,
                final List<WindowState> systemDialogs,
                final boolean available) {
            mFocused = Collections.unmodifiableMap(
                    new LinkedHashMap<>(focused));
            mTaskWindows = Collections.unmodifiableList(
                    new ArrayList<>(taskWindows));
            mSystemDialogs = Collections.unmodifiableList(
                    new ArrayList<>(systemDialogs));
            this.available = available;
        }

        static WindowSnapshot unavailable() {
            return new WindowSnapshot(
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    false);
        }

        List<FocusedWindow> focusedWindows() {
            return new ArrayList<>(mFocused.values());
        }

        FocusedWindow focusedWindow(final int displayId) {
            return mFocused.get(Integer.valueOf(displayId));
        }

        List<WindowState> systemDialogs() {
            return mSystemDialogs;
        }

        WindowState processWindow(
                final int displayId,
                final int taskId,
                final String processPackage) {
            if (processPackage == null || processPackage.isEmpty()) {
                return null;
            }
            for (final WindowState window : mTaskWindows) {
                if (window.displayId == displayId
                        && window.taskId == taskId
                        && processPackage.equals(window.packageName)) {
                    return window;
                }
            }
            return null;
        }

        WindowState focusedDialogFor(
                final int displayId,
                final int taskId,
                final String packageName) {
            for (final FocusedWindow focused : mFocused.values()) {
                if (focused.displayId != displayId
                        || !focused.isSystemDialog()) {
                    continue;
                }
                if (focused.taskId == taskId
                        || (!focused.packageName.isEmpty()
                                && focused.packageName.equals(packageName))) {
                    return focused;
                }
            }
            return null;
        }

        WindowState errorDialogFor(
                final int displayId, final String packageName) {
            if (packageName == null || packageName.isEmpty()) {
                return null;
            }
            for (final WindowState dialog : mSystemDialogs) {
                if (dialog.displayId == displayId
                        && dialog.isErrorDialog()
                        && packageName.equals(dialog.packageName)) {
                    return dialog;
                }
            }
            return null;
        }

        boolean hasErrorDialogForPackage(final String packageName) {
            if (packageName == null || packageName.isEmpty()) {
                return false;
            }
            for (final WindowState dialog : mSystemDialogs) {
                if (dialog.isErrorDialog()
                        && packageName.equals(dialog.packageName)) {
                    return true;
                }
            }
            return false;
        }
    }

    static class WindowState {
        static final String KIND_APPLICATION = "application";
        static final String KIND_CRASH_DIALOG = "crash_dialog";
        static final String KIND_ANR_DIALOG = "anr_dialog";
        static final String KIND_SYSTEM_DIALOG = "system_dialog";
        static final String KIND_UNKNOWN = "unknown";

        final int displayId;
        final String windowId;
        final String title;
        final int taskId;
        final int ownerPid;
        final int ownerUid;
        final String packageName;
        final String kind;
        final boolean notVisible;
        final boolean cloned;

        WindowState(
                final int displayId,
                final String windowId,
                final String title,
                final int taskId,
                final int ownerPid,
                final int ownerUid,
                final String packageName,
                final String kind,
                final boolean notVisible,
                final boolean cloned) {
            this.displayId = displayId;
            this.windowId = windowId == null ? "" : windowId;
            this.title = title == null ? "" : title;
            this.taskId = taskId;
            this.ownerPid = ownerPid;
            this.ownerUid = ownerUid;
            this.packageName = packageName == null ? "" : packageName;
            this.kind = kind == null ? KIND_UNKNOWN : kind;
            this.notVisible = notVisible;
            this.cloned = cloned;
        }

        boolean isSystemDialog() {
            return KIND_CRASH_DIALOG.equals(kind)
                    || KIND_ANR_DIALOG.equals(kind)
                    || KIND_SYSTEM_DIALOG.equals(kind);
        }

        boolean isErrorDialog() {
            return KIND_CRASH_DIALOG.equals(kind)
                    || KIND_ANR_DIALOG.equals(kind);
        }
    }

    static final class FocusedWindow extends WindowState {
        final int applicationTaskId;

        FocusedWindow(
                final int displayId,
                final String windowId,
                final String title,
                final int taskId,
                final int applicationTaskId,
                final int ownerPid,
                final int ownerUid,
                final String packageName,
                final String kind) {
            super(displayId, windowId, title, taskId, ownerPid, ownerUid,
                    packageName, kind, false, false);
            this.applicationTaskId = applicationTaskId;
        }

        int effectiveTaskId() {
            return taskId >= 0 ? taskId : applicationTaskId;
        }
    }

    private static final class Name {
        final String windowId;
        final String title;

        Name(final String windowId, final String title) {
            this.windowId = windowId;
            this.title = title;
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
