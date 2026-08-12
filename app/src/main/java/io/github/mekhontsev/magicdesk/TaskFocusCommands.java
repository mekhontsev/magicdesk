package io.github.mekhontsev.magicdesk;

final class TaskFocusCommands {
    private static final String TASK_WINDOWING_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskWindowingCommand";

    private TaskFocusCommands() {
    }

    static String createShellCommand(
            final int displayId,
            final Iterable<Integer> taskIds) {
        if (displayId < 0) {
            throw new IllegalArgumentException("invalid display id");
        }
        final StringBuilder arguments = new StringBuilder("focus ")
                .append(displayId);
        int taskCount = 0;
        for (final Integer taskId : taskIds) {
            if (taskId == null || taskId.intValue() < 0) {
                throw new IllegalArgumentException("invalid task id");
            }
            arguments.append(' ').append(taskId.intValue());
            taskCount++;
        }
        if (taskCount == 0) {
            throw new IllegalArgumentException("no task ids");
        }
        return AppProcessCommand.run(
                TASK_WINDOWING_COMMAND,
                arguments.toString());
    }
}
