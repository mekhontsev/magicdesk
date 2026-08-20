package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Backend boundary for commands declared by Desktop Entry Exec fields. */
final class DesktopExecRunner {
    enum StartResult {
        STARTED,
        PERMISSION_REQUESTED,
        UNAVAILABLE
    }

    interface Completion {
        void complete(ShellAccess.CommandResult result, Throwable error);
    }

    private static final ExecutorService SHELL_COMMANDS =
            Executors.newFixedThreadPool(4, runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskDesktopExec");
                thread.setDaemon(true);
                return thread;
            });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DesktopExecRunner() {
    }

    static StartResult prepareBackend(
            final Activity activity,
            final DesktopExecBackend backend) {
        if (backend == DesktopExecBackend.TERMUX) {
            if (!TermuxIntegration.isInstalled(activity)) {
                return StartResult.UNAVAILABLE;
            }
            return TermuxIntegration.ensureRunCommandPermission(activity)
                    ? StartResult.STARTED
                    : StartResult.PERMISSION_REQUESTED;
        }
        return ShellAccess.isReady()
                ? StartResult.STARTED : StartResult.UNAVAILABLE;
    }

    static StartResult runBackground(
            final Activity activity,
            final DesktopExecBackend backend,
            final String command,
            final String workingDirectory,
            final String label,
            final Completion completion) {
        final String prepared = DesktopExecCommand.normalize(command);
        if (prepared.isEmpty()) {
            return StartResult.UNAVAILABLE;
        }
        final StartResult availability = prepareBackend(activity, backend);
        if (availability != StartResult.STARTED) {
            return availability;
        }
        if (backend == DesktopExecBackend.TERMUX) {
            try {
                TermuxIntegration.runBackgroundShellCommand(
                        activity,
                        prepared,
                        label,
                        DesktopExecWorkingDirectory.normalize(
                                workingDirectory));
                return StartResult.STARTED;
            } catch (RuntimeException error) {
                notifyCompletion(completion, null, error);
                return StartResult.STARTED;
            }
        }
        SHELL_COMMANDS.execute(() -> {
            try {
                final ShellAccess.CommandResult result =
                        ShellAccess.executeForConsole(
                                withWorkingDirectory(
                                        prepared, workingDirectory));
                notifyCompletion(completion, result, null);
            } catch (IOException | RuntimeException error) {
                notifyCompletion(completion, null, error);
            }
        });
        return StartResult.STARTED;
    }

    static StartResult runTermuxForeground(
            final Activity activity,
            final String command,
            final String workingDirectory,
            final String label,
            final String sessionId) {
        final String prepared = DesktopExecCommand.normalize(command);
        if (prepared.isEmpty()) {
            return StartResult.UNAVAILABLE;
        }
        final StartResult availability = prepareBackend(
                activity, DesktopExecBackend.TERMUX);
        if (availability != StartResult.STARTED) {
            return availability;
        }
        TermuxIntegration.runForegroundShellCommand(
                activity,
                prepared,
                label,
                DesktopExecWorkingDirectory.normalize(workingDirectory),
                sessionId);
        return StartResult.STARTED;
    }

    static String diagnostics(final Context context) {
        return "shell=" + ShellAccess.isReady()
                + ", termux=" + TermuxIntegration.isInstalled(context)
                + ", termuxRunCommand="
                + (context.checkSelfPermission(
                        TermuxIntegration.RUN_COMMAND_PERMISSION)
                        == PackageManager.PERMISSION_GRANTED)
                + ", shellCapabilities={"
                + DesktopExecBackend.SHELL.capabilities().report()
                + "}, termuxCapabilities={"
                + DesktopExecBackend.TERMUX.capabilities().report()
                + "}";
    }

    private static void notifyCompletion(
            final Completion completion,
            final ShellAccess.CommandResult result,
            final Throwable error) {
        if (completion == null) {
            return;
        }
        MAIN.post(() -> completion.complete(result, error));
    }

    private static String withWorkingDirectory(
            final String command,
            final String workingDirectory) {
        final String directory = DesktopExecWorkingDirectory.normalize(
                workingDirectory);
        if (directory.isEmpty()) {
            return command;
        }
        return "cd -- " + ShellCommandLine.quote(directory)
                + " && " + command;
    }
}
