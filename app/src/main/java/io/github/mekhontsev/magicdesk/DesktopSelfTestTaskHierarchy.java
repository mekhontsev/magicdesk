package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads exact task-parent state without relying on dumpsys text geometry. */
final class DesktopSelfTestTaskHierarchy {
    private static final String COMMAND_CLASS =
            "io.github.mekhontsev.magicdesk.TaskHierarchyCommand";

    private DesktopSelfTestTaskHierarchy() {
    }

    static Snapshot inspect(final int displayId, final int taskId)
            throws IOException {
        return parse(ShellAccess.run(AppProcessCommand.run(
                COMMAND_CLASS, displayId + " " + taskId)));
    }

    static Snapshot parse(final String output) throws IOException {
        if (output == null) {
            throw new IOException("task hierarchy output is unavailable");
        }
        for (final String rawLine : output.split("\\r?\\n")) {
            final String line = rawLine.trim();
            if (!line.startsWith("task-hierarchy ")) {
                continue;
            }
            final Map<String, String> fields = new LinkedHashMap<>();
            for (final String field : line.substring(
                    "task-hierarchy ".length()).split("\\s+")) {
                final int separator = field.indexOf('=');
                if (separator > 0 && separator < field.length() - 1) {
                    fields.put(
                            field.substring(0, separator),
                            field.substring(separator + 1));
                }
            }
            try {
                return new Snapshot(
                        Integer.parseInt(require(fields, "task")),
                        Integer.parseInt(require(fields, "display")),
                        Integer.parseInt(require(fields, "feature")),
                        Integer.parseInt(require(fields, "mode")),
                        Boolean.parseBoolean(require(fields, "visible")),
                        Boolean.parseBoolean(require(fields, "focused")));
            } catch (NumberFormatException error) {
                throw new IOException(
                        "invalid task hierarchy output: " + line, error);
            }
        }
        throw new IOException("task hierarchy result is missing: "
                + output.trim());
    }

    private static String require(
            final Map<String, String> fields,
            final String name) throws IOException {
        final String value = fields.get(name);
        if (value == null) {
            throw new IOException("task hierarchy field is missing: " + name);
        }
        return value;
    }

    static final class Snapshot {
        final int taskId;
        final int displayId;
        final int featureId;
        final int windowingMode;
        final boolean visible;
        final boolean focused;

        Snapshot(
                final int taskId,
                final int displayId,
                final int featureId,
                final int windowingMode,
                final boolean visible,
                final boolean focused) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.featureId = featureId;
            this.windowingMode = windowingMode;
            this.visible = visible;
            this.focused = focused;
        }

        @Override
        public String toString() {
            return "task=" + taskId
                    + "/display=" + displayId
                    + "/feature=" + featureId
                    + "/mode=" + windowingMode
                    + "/visible=" + visible
                    + "/focused=" + focused;
        }
    }
}
