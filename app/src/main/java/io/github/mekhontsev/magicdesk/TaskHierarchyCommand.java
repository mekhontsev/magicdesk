package io.github.mekhontsev.magicdesk;

/** Reports the task hierarchy fields needed by strict desktop self-tests. */
public final class TaskHierarchyCommand {
    private TaskHierarchyCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 2) {
            System.err.println(
                    "usage: TaskHierarchyCommand <display-id> <task-id>");
            System.exit(64);
            return;
        }
        try {
            final int displayId = parseNonNegative(args[0], "display id");
            final int taskId = parseNonNegative(args[1], "task id");
            final Object task = HiddenTaskApi.requireTask(
                    HiddenTaskApi.getService(), displayId, taskId);
            System.out.println("task-hierarchy"
                    + " task=" + taskId
                    + " display=" + HiddenTaskApi.getTaskDisplayId(task)
                    + " feature=" + HiddenTaskApi.getIntField(
                            task, "displayAreaFeatureId")
                    + " mode=" + HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                    + " visible=" + HiddenTaskApi.getBooleanField(
                            task, "isVisible")
                    + " focused=" + HiddenTaskApi.getBooleanField(
                            task, "isFocused"));
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("task hierarchy query failed: "
                    + usefulMessage(error));
            System.exit(1);
        }
    }

    private static int parseNonNegative(
            final String value,
            final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
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
