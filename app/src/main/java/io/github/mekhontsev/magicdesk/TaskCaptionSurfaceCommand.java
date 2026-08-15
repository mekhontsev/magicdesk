package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reports whether SurfaceFlinger is still composing a task caption. */
public final class TaskCaptionSurfaceCommand {
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final long DUMP_TIMEOUT_MILLIS = 3_000L;
    private static final int DUMP_LIMIT_BYTES = 2 * 1024 * 1024;
    private static final Pattern CAPTION_LAYER = Pattern.compile(
            "^\\s*Layer \\[.*?] Caption of Task=(\\d+)#.*$");
    private static final Pattern VISIBILITY = Pattern.compile(
            "^\\s*(visible|invisible) reason=.*$");
    private static final Pattern RESULT = Pattern.compile(
            "(?m)^caption-surface=(visible|hidden|absent) task=(\\d+)$");

    private TaskCaptionSurfaceCommand() {
    }

    static String createCommand(final int... taskIds) {
        if (taskIds == null || taskIds.length == 0) {
            throw new IllegalArgumentException("at least one task is required");
        }
        final StringBuilder arguments = new StringBuilder();
        for (final int taskId : taskIds) {
            if (taskId < 0) {
                throw new IllegalArgumentException("invalid task id");
            }
            arguments.append(' ').append(taskId);
        }
        return AppProcessCommand.run(
                TaskCaptionSurfaceCommand.class.getName(),
                arguments.substring(1));
    }

    public static void main(final String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        final int[] taskIds = new int[args.length];
        try {
            for (int index = 0; index < args.length; index++) {
                taskIds[index] = Integer.parseInt(args[index]);
                if (taskIds[index] < 0) {
                    throw new NumberFormatException("negative task id");
                }
            }
            final Process process = new ProcessBuilder(
                    DUMPSYS, "SurfaceFlinger", "--layers")
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(
                            process,
                            DUMP_TIMEOUT_MILLIS,
                            DUMP_LIMIT_BYTES);
            if (result.exitCode != 0) {
                throw new IOException(
                        "SurfaceFlinger dump exited " + result.exitCode);
            }
            if (result.truncated) {
                throw new IOException("SurfaceFlinger dump was truncated");
            }
            final Map<Integer, State> states = inspect(result.output, taskIds);
            for (final int taskId : taskIds) {
                System.out.printf(Locale.US,
                        "caption-surface=%s task=%d%n",
                        states.get(Integer.valueOf(taskId)).label,
                        Integer.valueOf(taskId));
            }
        } catch (IOException | InterruptedException | NumberFormatException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("caption surface inspection failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    static Map<Integer, State> parseResult(
            final String output,
            final int... taskIds) throws IOException {
        final Map<Integer, State> states = new LinkedHashMap<>();
        final Matcher matcher = RESULT.matcher(output == null ? "" : output);
        while (matcher.find()) {
            states.put(
                    Integer.valueOf(Integer.parseInt(matcher.group(2))),
                    State.fromLabel(matcher.group(1)));
        }
        for (final int taskId : taskIds) {
            if (!states.containsKey(Integer.valueOf(taskId))) {
                throw new IOException(
                        "caption surface result missing task " + taskId);
            }
        }
        return states;
    }

    static Map<Integer, State> inspect(
            final String dump,
            final int... taskIds) {
        final Map<Integer, State> states = new LinkedHashMap<>();
        for (final int taskId : taskIds) {
            states.put(Integer.valueOf(taskId), State.ABSENT);
        }
        Integer pendingTaskId = null;
        for (final String line : (dump == null ? "" : dump).split("\\R")) {
            final Matcher layer = CAPTION_LAYER.matcher(line);
            if (layer.matches()) {
                final int taskId = Integer.parseInt(layer.group(1));
                pendingTaskId = states.containsKey(Integer.valueOf(taskId))
                        ? Integer.valueOf(taskId) : null;
                continue;
            }
            if (pendingTaskId == null) {
                continue;
            }
            final Matcher visibility = VISIBILITY.matcher(line);
            if (!visibility.matches()) {
                continue;
            }
            final State observed = "visible".equals(visibility.group(1))
                    ? State.VISIBLE : State.HIDDEN;
            final State previous = states.get(pendingTaskId);
            if (previous != State.VISIBLE) {
                states.put(pendingTaskId, observed);
            }
            pendingTaskId = null;
        }
        return states;
    }

    enum State {
        VISIBLE("visible"),
        HIDDEN("hidden"),
        ABSENT("absent");

        final String label;

        State(final String label) {
            this.label = label;
        }

        static State fromLabel(final String label) {
            for (final State state : values()) {
                if (state.label.equals(label)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("invalid caption surface state");
        }
    }

    private static void usage() {
        System.err.println(
                "usage: TaskCaptionSurfaceCommand <task-id> [task-id...]");
        System.exit(64);
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
