package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reconciles Nubia's desktop repository before returning to Home. */
final class PhoneDesktopTaskRecovery {
    private static final String TAG = "MagicDeskPhoneRecovery";
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final String CMD = "/system/bin/cmd";
    private static final String RECOVERY_COMMAND =
            "io.github.mekhontsev.magicdesk.PhoneDesktopTaskRecoveryCommand";
    private static final String REPOSITORY_DUMP =
            "/system/bin/dumpsys activity service "
                    + "com.android.systemui/.SystemUIService"
                    + " | /system/bin/awk 'BEGIN { found=0; done=0; base=0 } "
                    + "{ line=$0; stripped=line; sub(/^[ ]*/, \"\", stripped); "
                    + "indent=length(line)-length(stripped); "
                    + "if (!found && !done "
                    + "&& stripped == \"DesktopUserRepositories:\") "
                    + "{ found=1; base=indent } "
                    + "else if (found && stripped != \"\" && indent <= base) "
                    + "{ found=0; done=1 } if (found) print line }'";
    private static final String FULLSCREEN_COMMAND =
            "io.github.mekhontsev.magicdesk."
                    + "TaskClientPreservingFullscreenTransitionCommand";
    private static final Continuation ALWAYS_CONTINUE = () -> true;
    private static final Environment SYSTEM_ENVIRONMENT = new Environment() {
        @Override
        public boolean isReady() {
            return ShellAccess.isReady();
        }

        @Override
        public CommandResult run(final String command) {
            try {
                return CommandResult.success(ShellAccess.run(command));
            } catch (IOException error) {
                Log.d(TAG, "privileged command unavailable: " + command
                        + ": " + error.getMessage());
                return CommandResult.failure(error.getMessage() == null
                        ? "I/O error" : error.getMessage());
            }
        }
    };

    private PhoneDesktopTaskRecovery() {
    }

    static String repositoryDumpCommand() {
        return REPOSITORY_DUMP;
    }

    static void recover(final Callback callback) {
        recover(-1, false, ALWAYS_CONTINUE, callback);
    }

    static void recoverRemovedDisplay(
            final int removedDisplayId,
            final Callback callback) {
        recover(removedDisplayId, false, ALWAYS_CONTINUE, callback);
    }

    static void recoverRemovedDisplayAfterTimeout(
            final int removedDisplayId,
            final Callback callback) {
        recover(removedDisplayId, true, ALWAYS_CONTINUE, callback);
    }

    private static void recover(
            final int removedDisplayId,
            final boolean allowUnsettledRemoval,
            final Continuation continuation,
            final Callback callback) {
        TaskCommandQueue.execute(() -> {
            final Result result = recoverNow(
                    removedDisplayId,
                    allowUnsettledRemoval,
                    continuation == null ? ALWAYS_CONTINUE : continuation,
                    SYSTEM_ENVIRONMENT);
            if (callback != null) {
                callback.onComplete(result);
            }
        });
    }

    static Result recoverBlocking() {
        return recoverBlocking(ALWAYS_CONTINUE);
    }

    static Result recoverBlocking(final Continuation continuation) {
        try {
            return TaskCommandQueue.call(() -> recoverNow(
                    -1,
                    false,
                    continuation == null ? ALWAYS_CONTINUE : continuation,
                    SYSTEM_ENVIRONMENT));
        } catch (RuntimeException error) {
            Log.w(TAG, "phone desktop recovery queue failed", error);
            return Result.failure(usefulMessage(error));
        }
    }

    static Result recoverForTest(
            final Continuation continuation,
            final Environment environment) {
        return recoverNow(
                -1,
                false,
                continuation,
                environment);
    }

    static Result recoverRemovedDisplayForTest(
            final int removedDisplayId,
            final boolean allowUnsettledRemoval,
            final Continuation continuation,
            final Environment environment) {
        return recoverNow(
                removedDisplayId,
                allowUnsettledRemoval,
                continuation,
                environment);
    }

    static Result recoverRemovedDisplayForTest(
            final int removedDisplayId,
            final Continuation continuation,
            final Environment environment) {
        return recoverRemovedDisplayForTest(
                removedDisplayId, false, continuation, environment);
    }

    private static Result recoverNow(
            final int removedDisplayId,
            final boolean allowUnsettledRemoval,
            final Continuation continuation,
            final Environment environment) {
        if (!environment.isReady()) {
            return Result.success("phone desktop recovery unavailable");
        }
        if (cancelled(continuation)) {
            return Result.cancelled();
        }
        final CommandResult stack = runRead(
                CMD + " activity stack list", continuation, environment);
        if (stack.cancelled) {
            return Result.cancelled();
        }
        if (!stack.success) {
            return Result.failure(stack.output.trim());
        }
        final CommandResult repository = runRead(
                repositoryDumpCommand(), continuation, environment);
        if (repository.cancelled) {
            return Result.cancelled();
        }
        if (!repository.success) {
            return Result.failure(repository.output.trim());
        }
        final boolean removedDisplaySettled = removedDisplayId <= 0
                || removedDisplayTransitionSettled(
                        stack.output,
                        repository.output,
                        removedDisplayId);
        if (!removedDisplaySettled && !allowUnsettledRemoval) {
            return Result.pending(
                    "waiting for tasks from removed display "
                            + removedDisplayId);
        }
        Map<Integer, PhoneTask> liveTasks = indexPhoneTasks(stack.output);
        final Set<Integer> unavailableRemovedTaskIds = new LinkedHashSet<>();
        if (!removedDisplaySettled) {
            unavailableRemovedTaskIds.addAll(
                    SystemUiDesktopRepositoryParser.parseTaskIds(
                            repository.output, removedDisplayId));
            unavailableRemovedTaskIds.removeAll(liveTasks.keySet());
        }
        final Set<Integer> phoneRepositoryTaskIds = new LinkedHashSet<>(
                SystemUiDesktopRepositoryParser.parseTaskIds(
                        repository.output, 0));
        excludeMagicDeskTasks(phoneRepositoryTaskIds, liveTasks);
        final Set<Integer> missingTaskIds = new LinkedHashSet<>(
                phoneRepositoryTaskIds);
        missingTaskIds.removeAll(liveTasks.keySet());
        if (!missingTaskIds.isEmpty()) {
            final CommandResult revived = runMutation(
                    createReviveCommand(missingTaskIds),
                    continuation,
                    environment);
            if (revived.cancelled) {
                return Result.cancelled();
            }
            if (!revived.success) {
                return Result.failure(revived.output.trim());
            }
            liveTasks = waitForPhoneTasks(
                    missingTaskIds, continuation, environment);
            if (cancelled(continuation)) {
                return Result.cancelled();
            }
            if (!liveTasks.keySet().containsAll(missingTaskIds)) {
                return Result.failure(
                        revived.output.trim()
                                + "; SystemUI retains unavailable phone tasks "
                                + missingTaskIds);
            }
        }

        if (!unavailableRemovedTaskIds.isEmpty()) {
            final CommandResult revived = runMutation(
                    createReviveCommand(unavailableRemovedTaskIds),
                    continuation,
                    environment);
            if (revived.cancelled) {
                return Result.cancelled();
            }
            if (revived.success) {
                liveTasks = waitForPhoneTasks(
                        unavailableRemovedTaskIds,
                        continuation,
                        environment);
            } else {
                final CommandResult currentStack = runRead(
                        CMD + " activity stack list",
                        continuation,
                        environment);
                if (currentStack.cancelled) {
                    return Result.cancelled();
                }
                if (!currentStack.success) {
                    return Result.failure(currentStack.output.trim());
                }
                liveTasks = indexPhoneTasks(currentStack.output);
            }
            unavailableRemovedTaskIds.removeAll(liveTasks.keySet());
        }

        final Set<Integer> taskIds = new LinkedHashSet<>(
                phoneRepositoryTaskIds);
        if (removedDisplayId > 0) {
            final Set<Integer> removedDisplayTaskIds = new LinkedHashSet<>(
                    SystemUiDesktopRepositoryParser.parseTaskIds(
                            repository.output, removedDisplayId));
            removedDisplayTaskIds.retainAll(liveTasks.keySet());
            excludeMagicDeskTasks(removedDisplayTaskIds, liveTasks);
            taskIds.addAll(removedDisplayTaskIds);
        }
        for (final PhoneTask task : liveTasks.values()) {
            if (task.freeform && !task.home) {
                taskIds.add(Integer.valueOf(task.taskId));
            }
        }
        if (taskIds.isEmpty() && removedDisplayId <= 0) {
            return Result.success("phone-desktop-recovery candidates=0");
        }
        if (taskIds.isEmpty() && !unavailableRemovedTaskIds.isEmpty()) {
            return Result.failure(
                    "SystemUI retains unavailable tasks for removed display "
                            + removedDisplayId + ": "
                            + unavailableRemovedTaskIds);
        }

        for (final Integer taskId : taskIds) {
            final PhoneTask task = liveTasks.get(taskId);
            if (!isRecoverable(task)) {
                return Result.failure(
                        "phone desktop task unavailable: " + taskId);
            }
            if (!task.freeform) {
                final CommandResult enteredDesktop = runMutation(
                        CMD + " window shell desktopmode moveTaskToDesk "
                                + taskId.intValue(),
                        continuation,
                        environment);
                if (enteredDesktop.cancelled) {
                    return Result.cancelled();
                }
                if (!enteredDesktop.success
                        || !waitForTaskMode(
                                taskId.intValue(),
                                true,
                                continuation,
                                environment)) {
                    return cancelled(continuation)
                            ? Result.cancelled()
                            : Result.failure(
                                    "could not prepare phone desktop task "
                                            + taskId + ": "
                                            + enteredDesktop.output.trim());
                }
            }

            final CommandResult fullscreen = runMutation(
                    createFullscreenCommand(taskId.intValue()),
                    continuation,
                    environment);
            if (fullscreen.cancelled) {
                return Result.cancelled();
            }
            if (!fullscreen.success
                    || !waitForTaskMode(
                            taskId.intValue(),
                            false,
                            continuation,
                            environment)) {
                return cancelled(continuation)
                        ? Result.cancelled()
                        : Result.failure(
                                "native fullscreen transition failed for task "
                                        + taskId + ": "
                                        + fullscreen.output.trim());
            }
        }

        for (int attempt = 0; attempt < 20; attempt++) {
            final CommandResult currentStack = runRead(
                    CMD + " activity stack list", continuation, environment);
            final CommandResult currentRepository = runRead(
                    repositoryDumpCommand(), continuation, environment);
            if (currentStack.cancelled || currentRepository.cancelled) {
                return Result.cancelled();
            }
            if (!currentStack.success || !currentRepository.success) {
                return Result.failure(!currentStack.success
                        ? currentStack.output.trim()
                        : currentRepository.output.trim());
            }
            final Set<Integer> remaining = new LinkedHashSet<>(
                    SystemUiDesktopRepositoryParser.parseTaskIds(
                            currentRepository.output, 0));
            final Map<Integer, PhoneTask> currentTasks =
                    indexPhoneTasks(currentStack.output);
            excludeMagicDeskTasks(remaining, currentTasks);
            final Set<Integer> unavailableRemovedTaskIdsNow =
                    new LinkedHashSet<>();
            if (removedDisplayId > 0) {
                final Set<Integer> removedDisplayTaskIds =
                        new LinkedHashSet<>(
                                SystemUiDesktopRepositoryParser.parseTaskIds(
                                        currentRepository.output,
                                        removedDisplayId));
                remaining.addAll(removedDisplayTaskIds);
                unavailableRemovedTaskIdsNow.addAll(removedDisplayTaskIds);
                unavailableRemovedTaskIdsNow.removeAll(currentTasks.keySet());
            }
            for (final PhoneTask task : currentTasks.values()) {
                if (task.freeform && isRecoverable(task)) {
                    remaining.add(Integer.valueOf(task.taskId));
                }
            }
            if (remaining.isEmpty()) {
                return Result.success(
                        "phone-desktop-recovery transitioned="
                                + taskIds.size());
            }
            if (!unavailableRemovedTaskIdsNow.isEmpty()) {
                return Result.failure(
                        "SystemUI retains unavailable tasks for removed display "
                                + removedDisplayId + ": "
                                + unavailableRemovedTaskIdsNow);
            }
            if (!sleepForStatePoll(continuation)) {
                return cancelled(continuation)
                        ? Result.cancelled()
                        : Result.failure(
                                "phone desktop recovery interrupted");
            }
        }
        return Result.failure("SystemUI still retains phone desktop tasks");
    }

    private static CommandResult runRead(
            final String command,
            final Continuation continuation,
            final Environment environment) {
        return cancelled(continuation)
                ? CommandResult.cancelled()
                : environment.run(command);
    }

    private static CommandResult runMutation(
            final String command,
            final Continuation continuation,
            final Environment environment) {
        return runRead(command, continuation, environment);
    }

    private static String createReviveCommand(final Set<Integer> taskIds) {
        final StringBuilder arguments = new StringBuilder();
        for (final Integer taskId : taskIds) {
            if (arguments.length() > 0) {
                arguments.append(' ');
            }
            arguments.append(taskId.intValue());
        }
        return AppProcessCommand.run(RECOVERY_COMMAND, arguments.toString());
    }

    private static String createFullscreenCommand(final int taskId) {
        return AppProcessCommand.run(
                FULLSCREEN_COMMAND, "0 " + taskId);
    }

    private static Map<Integer, PhoneTask> waitForPhoneTasks(
            final Set<Integer> taskIds,
            final Continuation continuation,
            final Environment environment) {
        Map<Integer, PhoneTask> tasks = Collections.emptyMap();
        for (int attempt = 0; attempt < 20; attempt++) {
            final CommandResult stack = runRead(
                    CMD + " activity stack list", continuation, environment);
            if (!stack.success) {
                return tasks;
            }
            tasks = indexPhoneTasks(stack.output);
            if (tasks.keySet().containsAll(taskIds)) {
                return tasks;
            }
            if (!sleepForStatePoll(continuation)) {
                return tasks;
            }
        }
        return tasks;
    }

    private static boolean waitForTaskMode(
            final int taskId,
            final boolean freeform,
            final Continuation continuation,
            final Environment environment) {
        for (int attempt = 0; attempt < 30; attempt++) {
            final CommandResult stack = runRead(
                    CMD + " activity stack list", continuation, environment);
            if (!stack.success) {
                return false;
            }
            final PhoneTask task = indexPhoneTasks(stack.output).get(
                    Integer.valueOf(taskId));
            if (task != null
                    && (freeform ? task.freeform : task.fullscreen)) {
                return true;
            }
            if (!sleepForStatePoll(continuation)) {
                return false;
            }
        }
        return false;
    }

    private static boolean sleepForStatePoll(
            final Continuation continuation) {
        if (cancelled(continuation)) {
            return false;
        }
        try {
            Thread.sleep(100L);
            return !cancelled(continuation);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Map<Integer, PhoneTask> indexPhoneTasks(
            final String stackOutput) {
        final Map<Integer, PhoneTask> result = new LinkedHashMap<>();
        final List<TaskStackParser.Entry> tasks =
                TaskStackParser.parse(stackOutput);
        for (final TaskStackParser.Entry task : tasks) {
            if (task.displayId != 0) {
                continue;
            }
            final boolean home = task.isHome();
            result.put(Integer.valueOf(task.taskId), new PhoneTask(
                    task.taskId,
                    task.packageName,
                    home,
                    "freeform".equals(task.windowingMode),
                    "fullscreen".equals(task.windowingMode)));
        }
        return result;
    }

    static boolean removedDisplayTransitionSettled(
            final String stackOutput,
            final String repositoryOutput,
            final int removedDisplayId) {
        final Set<Integer> removedTaskIds =
                SystemUiDesktopRepositoryParser.parseTaskIds(
                        repositoryOutput, removedDisplayId);
        if (removedTaskIds.isEmpty()) {
            return true;
        }
        final Set<Integer> phoneTaskIds =
                indexPhoneTasks(stackOutput).keySet();
        return phoneTaskIds.containsAll(removedTaskIds);
    }

    private static void excludeMagicDeskTasks(
            final Set<Integer> taskIds,
            final Map<Integer, PhoneTask> liveTasks) {
        taskIds.removeIf(taskId -> {
            final PhoneTask task = liveTasks.get(taskId);
            return task != null
                    && MAGICDESK_PACKAGE.equals(task.packageName);
        });
    }

    static boolean isRecoverable(
            final String packageName,
            final boolean home) {
        return packageName != null
                && !home
                && !MAGICDESK_PACKAGE.equals(packageName);
    }

    private static boolean isRecoverable(final PhoneTask task) {
        return task != null && isRecoverable(task.packageName, task.home);
    }

    private static boolean cancelled(final Continuation continuation) {
        return continuation != null && !continuation.shouldContinue();
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

    interface Continuation {
        boolean shouldContinue();
    }

    interface Callback {
        void onComplete(Result result);
    }

    interface Environment {
        boolean isReady();

        CommandResult run(String command);
    }

    static final class Result {
        final boolean success;
        final boolean cancelled;
        final boolean pending;
        final String message;

        private Result(
                final boolean success,
                final boolean cancelled,
                final boolean pending,
                final String message) {
            this.success = success;
            this.cancelled = cancelled;
            this.pending = pending;
            this.message = message;
        }

        static Result success(final String message) {
            return new Result(true, false, false, message);
        }

        static Result failure(final String message) {
            return new Result(false, false, false, message);
        }

        static Result pending(final String message) {
            return new Result(true, false, true, message);
        }

        static Result cancelled() {
            return new Result(true, true, false,
                    "cleanup superseded by a newer local desktop");
        }
    }

    static final class CommandResult {
        final boolean success;
        final boolean cancelled;
        final String output;

        private CommandResult(
                final boolean success,
                final boolean cancelled,
                final String output) {
            this.success = success;
            this.cancelled = cancelled;
            this.output = output == null ? "" : output;
        }

        static CommandResult success(final String output) {
            return new CommandResult(true, false, output);
        }

        static CommandResult failure(final String output) {
            return new CommandResult(false, false, output);
        }

        static CommandResult cancelled() {
            return new CommandResult(false, true, "cancelled");
        }
    }

    private static final class PhoneTask {
        final int taskId;
        final String packageName;
        final boolean home;
        final boolean freeform;
        final boolean fullscreen;

        PhoneTask(
                final int taskId,
                final String packageName,
                final boolean home,
                final boolean freeform,
                final boolean fullscreen) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.home = home;
            this.freeform = freeform;
            this.fullscreen = fullscreen;
        }
    }
}
