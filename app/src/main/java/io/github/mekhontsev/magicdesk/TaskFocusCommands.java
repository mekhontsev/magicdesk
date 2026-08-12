package io.github.mekhontsev.magicdesk;

final class TaskFocusCommands {
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";

    private TaskFocusCommands() {
    }

    static String createShellCommand(final Iterable<Integer> taskIds) {
        final StringBuilder arguments = new StringBuilder("focus-stack");
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
                TASK_CONTROL_COMMAND, arguments.toString());
    }
}
