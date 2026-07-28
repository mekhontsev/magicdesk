package io.github.mekhontsev.magicdesk;

final class TaskFocusCommands {
    private static final String AM = "/system/bin/am";

    private TaskFocusCommands() {
    }

    static String createShellCommand(final Iterable<Integer> taskIds) {
        final StringBuilder command = new StringBuilder();
        for (final Integer taskId : taskIds) {
            if (taskId == null || taskId.intValue() < 0) {
                throw new IllegalArgumentException("invalid task id");
            }
            if (command.length() > 0) {
                command.append(" && ");
            }
            command.append(AM).append(" task focus ")
                    .append(taskId.intValue());
        }
        if (command.length() == 0) {
            throw new IllegalArgumentException("no task ids");
        }
        return command.toString();
    }
}
