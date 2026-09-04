package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reconciles retained WMShell desktop tasks before returning to phone Home. */
final class PhoneDesktopTaskRecovery {
    private static final String TAG = "MagicDeskPhoneRecovery";
    private static final String MAGICDESK_PACKAGE =
            DesktopHostComponents.PACKAGE_NAME;
    private static final List<String> TRANSIENT_MAGICDESK_CLASSES =
            Arrays.asList(
                    DesktopHostComponents.EXTERNAL_HOME_CLASS,
                    DesktopHostComponents.PHONE_HOME_CLASS,
                    MAGICDESK_PACKAGE + ".DesktopSelfTestActivity",
                    MAGICDESK_PACKAGE + ".DesktopSelfTestBrowserActivity",
                    MAGICDESK_PACKAGE + ".MagicDeskTouchpadActivity");
    private static final String CMD = "/system/bin/cmd";
    private static final String WMSHELL_HELP =
            CMD + " statusbar wmshell-passthrough help";
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

        @Override
        public TaskReadResult readTasks() {
            try {
                return TaskReadResult.success(indexPhoneTasks(
                        ShellAccess.readTaskSnapshots(-1, 200)));
            } catch (IOException error) {
                return TaskReadResult.failure(usefulMessage(error));
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

    static void recover(
            final Continuation continuation,
            final Callback callback) {
        recover(-1, false, continuation, callback);
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
            final Result result = requiresRecovery()
                    ? recoverNow(
                            removedDisplayId,
                            allowUnsettledRemoval,
                            continuation == null
                                    ? ALWAYS_CONTINUE : continuation,
                            SYSTEM_ENVIRONMENT)
                    : Result.success(
                            "phone desktop recovery is not required");
            if (callback != null) {
                callback.onComplete(result);
            }
        });
    }

    static Result recoverBlocking() {
        return recoverBlocking(ALWAYS_CONTINUE);
    }

    static Result recoverBlocking(final Continuation continuation) {
        if (!requiresRecovery()) {
            return Result.success("phone desktop recovery is not required");
        }
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

    private static boolean requiresRecovery() {
        return PlatformDrivers.current().windowing()
                .requiresPhoneTaskRecovery();
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
        final TaskReadResult stack = readTasks(continuation, environment);
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
                        stack.tasks,
                        repository.output,
                        removedDisplayId);
        if (!removedDisplaySettled && !allowUnsettledRemoval) {
            return Result.pending(
                    "waiting for tasks from removed display "
                            + removedDisplayId);
        }
        Map<Integer, PhoneTask> liveTasks = stack.tasks;
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
        excludeNonRecoverableTasks(phoneRepositoryTaskIds, liveTasks);
        final Set<Integer> missingTaskIds = new LinkedHashSet<>(
                phoneRepositoryTaskIds);
        missingTaskIds.removeAll(liveTasks.keySet());
        final Set<Integer> unavailablePhoneTaskIds = new LinkedHashSet<>();
        if (!missingTaskIds.isEmpty()) {
            final CommandResult revived = runMutation(
                    createReviveCommand(missingTaskIds),
                    continuation,
                    environment);
            if (revived.cancelled) {
                return Result.cancelled();
            }
            if (revived.success) {
                liveTasks = waitForPhoneTasks(
                        missingTaskIds, continuation, environment);
            }
            if (cancelled(continuation)) {
                return Result.cancelled();
            }
            unavailablePhoneTaskIds.addAll(missingTaskIds);
            unavailablePhoneTaskIds.removeAll(liveTasks.keySet());
            // Revived tasks now have enough metadata to identify MagicDesk's
            // own transient windows; do not recover them as user apps.
            excludeNonRecoverableTasks(
                    phoneRepositoryTaskIds, liveTasks);
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
                final TaskReadResult currentStack = readTasks(
                        continuation, environment);
                if (currentStack.cancelled) {
                    return Result.cancelled();
                }
                if (!currentStack.success) {
                    return Result.failure(currentStack.output.trim());
                }
                liveTasks = currentStack.tasks;
            }
            unavailableRemovedTaskIds.removeAll(liveTasks.keySet());
        }

        final Set<Integer> taskIds = new LinkedHashSet<>(
                phoneRepositoryTaskIds);
        taskIds.retainAll(liveTasks.keySet());
        if (removedDisplayId > 0) {
            final Set<Integer> removedDisplayTaskIds = new LinkedHashSet<>(
                    SystemUiDesktopRepositoryParser.parseTaskIds(
                            repository.output, removedDisplayId));
            removedDisplayTaskIds.retainAll(liveTasks.keySet());
            excludeNonRecoverableTasks(
                    removedDisplayTaskIds, liveTasks);
            taskIds.addAll(removedDisplayTaskIds);
        }
        for (final PhoneTask task : liveTasks.values()) {
            if (task.freeform && isRecoverable(task)) {
                taskIds.add(Integer.valueOf(task.taskId));
            }
        }
        if (taskIds.isEmpty() && removedDisplayId <= 0) {
            return Result.success(recoverySummary(
                    0, unavailablePhoneTaskIds));
        }
        if (taskIds.isEmpty() && !unavailableRemovedTaskIds.isEmpty()) {
            return Result.failure(
                    "SystemUI retains unavailable tasks for removed display "
                            + removedDisplayId + ": "
                            + unavailableRemovedTaskIds);
        }

        String desktopMoveAction = null;
        for (final Integer taskId : taskIds) {
            final PhoneTask task = liveTasks.get(taskId);
            if (!isRecoverable(task)) {
                return Result.failure(
                        "phone desktop task unavailable: " + taskId);
            }
            if (!task.freeform) {
                if (desktopMoveAction == null) {
                    final CommandResult help = runRead(
                            WMSHELL_HELP, continuation, environment);
                    if (help.cancelled) {
                        return Result.cancelled();
                    }
                    desktopMoveAction = help.success
                            ? NativeDesktopController.selectMoveAction(
                                    help.output)
                            : null;
                    if (desktopMoveAction == null) {
                        return Result.failure(
                                "WMShell desktop command unavailable: "
                                        + help.output.trim());
                    }
                }
                final CommandResult enteredDesktop = runMutation(
                        CMD + " window shell desktopmode "
                                + desktopMoveAction + " "
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
            final TaskReadResult currentStack = readTasks(
                    continuation, environment);
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
                    currentStack.tasks;
            excludeNonRecoverableTasks(remaining, currentTasks);
            final Set<Integer> unavailablePhoneTaskIdsNow =
                    new LinkedHashSet<>(remaining);
            unavailablePhoneTaskIdsNow.removeAll(currentTasks.keySet());
            unavailablePhoneTaskIds.addAll(unavailablePhoneTaskIdsNow);
            unavailablePhoneTaskIds.removeAll(currentTasks.keySet());
            remaining.retainAll(currentTasks.keySet());
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
                return Result.success(recoverySummary(
                        taskIds.size(), unavailablePhoneTaskIds));
            }
            if (!unavailableRemovedTaskIdsNow.isEmpty()) {
                return Result.failure(
                        "SystemUI retains unavailable tasks for removed display "
                                + removedDisplayId + ": "
                                + unavailableRemovedTaskIdsNow);
            }
            if (cancelled(continuation)) {
                return cancelled(continuation)
                        ? Result.cancelled()
                        : Result.failure(
                                "phone desktop recovery interrupted");
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_WINDOWING_MODE, 100L);
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

    private static TaskReadResult readTasks(
            final Continuation continuation,
            final Environment environment) {
        return cancelled(continuation)
                ? TaskReadResult.cancelled()
                : environment.readTasks();
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
        final TaskReadResult result = BoundedStateAwaiter.awaitUnchecked(
                BoundedStateAwaiter.Reason.TASK_APPEARANCE,
                2_000L,
                100L,
                () -> readTasks(continuation, environment),
                current -> current.cancelled
                        || !current.success
                        || current.tasks.keySet().containsAll(taskIds));
        return result == null ? tasks : result.tasks;
    }

    private static boolean waitForTaskMode(
            final int taskId,
            final boolean freeform,
            final Continuation continuation,
            final Environment environment) {
        final TaskReadResult result = BoundedStateAwaiter.awaitUnchecked(
                BoundedStateAwaiter.Reason.TASK_WINDOWING_MODE,
                3_000L,
                100L,
                () -> readTasks(continuation, environment),
                current -> current.cancelled
                        || !current.success
                        || matchesMode(current.tasks.get(
                                Integer.valueOf(taskId)), freeform));
        return result != null
                && result.success
                && matchesMode(
                        result.tasks.get(Integer.valueOf(taskId)), freeform);
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
                    task.componentName,
                    home,
                    "freeform".equals(task.windowingMode),
                    "fullscreen".equals(task.windowingMode)));
        }
        return result;
    }

    private static Map<Integer, PhoneTask> indexPhoneTasks(
            final FrameworkTaskSnapshot[] snapshots) {
        final Map<Integer, PhoneTask> result = new LinkedHashMap<>();
        if (snapshots == null) {
            return result;
        }
        for (final FrameworkTaskSnapshot task : snapshots) {
            if (task == null || task.displayId != 0) {
                continue;
            }
            result.put(Integer.valueOf(task.taskId), new PhoneTask(
                    task.taskId,
                    task.packageName,
                    task.componentName,
                    task.isHome(),
                    task.windowingMode
                            == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM,
                    task.windowingMode
                            == FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN));
        }
        return result;
    }

    private static boolean matchesMode(
            final PhoneTask task,
            final boolean freeform) {
        return task != null && (freeform ? task.freeform : task.fullscreen);
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

    private static boolean removedDisplayTransitionSettled(
            final Map<Integer, PhoneTask> phoneTasks,
            final String repositoryOutput,
            final int removedDisplayId) {
        final Set<Integer> removedTaskIds =
                SystemUiDesktopRepositoryParser.parseTaskIds(
                        repositoryOutput, removedDisplayId);
        return removedTaskIds.isEmpty()
                || phoneTasks.keySet().containsAll(removedTaskIds);
    }

    private static void excludeNonRecoverableTasks(
            final Set<Integer> taskIds,
            final Map<Integer, PhoneTask> liveTasks) {
        taskIds.removeIf(taskId -> {
            final PhoneTask task = liveTasks.get(taskId);
            return task != null && !isRecoverable(task);
        });
    }

    static boolean isRecoverable(
            final String packageName,
            final boolean home) {
        return isRecoverable(packageName, null, home);
    }

    static boolean isRecoverable(
            final String packageName,
            final String componentName,
            final boolean home) {
        if (packageName == null || home) {
            return false;
        }
        return !MAGICDESK_PACKAGE.equals(packageName)
                || (componentName != null
                        && !isTransientMagicDeskComponent(componentName));
    }

    private static boolean isTransientMagicDeskComponent(
            final String componentName) {
        if (DesktopInfrastructureTasks.isComponentName(componentName)) {
            return true;
        }
        final String prefix = MAGICDESK_PACKAGE + "/";
        if (componentName == null || !componentName.startsWith(prefix)) {
            return false;
        }
        final String activityName = componentName.substring(prefix.length());
        final String normalized = activityName.startsWith(".")
                ? MAGICDESK_PACKAGE + activityName : activityName;
        return TRANSIENT_MAGICDESK_CLASSES.contains(normalized);
    }

    private static boolean isRecoverable(final PhoneTask task) {
        return task != null && isRecoverable(
                task.packageName, task.componentName, task.home);
    }

    private static String recoverySummary(
            final int transitioned,
            final Set<Integer> unavailableTaskIds) {
        final String summary = "phone-desktop-recovery transitioned="
                + transitioned;
        if (unavailableTaskIds.isEmpty()) {
            return summary;
        }
        return summary + " unavailable=" + unavailableTaskIds;
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

        TaskReadResult readTasks();
    }

    static TaskReadResult readTextTaskFixtureForTest(
            final CommandResult command) {
        return command.cancelled
                ? TaskReadResult.cancelled()
                : command.success
                        ? TaskReadResult.success(indexPhoneTasks(command.output))
                        : TaskReadResult.failure(command.output);
    }

    static final class TaskReadResult {
        final boolean success;
        final boolean cancelled;
        final String output;
        final Map<Integer, PhoneTask> tasks;

        private TaskReadResult(
                final boolean success,
                final boolean cancelled,
                final String output,
                final Map<Integer, PhoneTask> tasks) {
            this.success = success;
            this.cancelled = cancelled;
            this.output = output == null ? "" : output;
            this.tasks = tasks == null
                    ? Collections.emptyMap() : tasks;
        }

        static TaskReadResult success(
                final Map<Integer, PhoneTask> tasks) {
            return new TaskReadResult(true, false, "", tasks);
        }

        static TaskReadResult failure(final String output) {
            return new TaskReadResult(
                    false, false, output, Collections.emptyMap());
        }

        static TaskReadResult cancelled() {
            return new TaskReadResult(
                    false, true, "cancelled", Collections.emptyMap());
        }
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
        final String componentName;
        final boolean home;
        final boolean freeform;
        final boolean fullscreen;

        PhoneTask(
                final int taskId,
                final String packageName,
                final String componentName,
                final boolean home,
                final boolean freeform,
                final boolean fullscreen) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.componentName = componentName;
            this.home = home;
            this.freeform = freeform;
            this.fullscreen = fullscreen;
        }
    }
}
