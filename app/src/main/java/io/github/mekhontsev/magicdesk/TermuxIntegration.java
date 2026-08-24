package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

final class TermuxIntegration {
    static final String PACKAGE_NAME = "com.termux";
    static final String HOME_DIRECTORY =
            "/data/data/com.termux/files/home";
    static final String RUN_COMMAND_PERMISSION =
            "com.termux.permission.RUN_COMMAND";
    static final int PERMISSION_REQUEST_CODE = 7312;

    private static final String RUN_COMMAND_SERVICE =
            "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_COMMAND_PATH =
            "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS =
            "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR =
            "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND =
            "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_RUNNER =
            "com.termux.RUN_COMMAND_RUNNER";
    private static final String EXTRA_STDIN =
            "com.termux.RUN_COMMAND_STDIN";
    private static final String EXTRA_COMMAND_LABEL =
            "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_RESULT_PENDING_INTENT =
            "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String RUNNER_APP_SHELL = "app-shell";
    private static final String PTY_BOOTSTRAP =
            "set -eu\n"
            + "target=\"$7\"\n"
            + "mkdir -p \"${target%/*}\"\n"
            + "tmp=\"$target.tmp.$$\"\n"
            + "trap 'rm -f \"$tmp\"' EXIT HUP INT TERM\n"
            + "base64 -d > \"$tmp\"\n"
            + "chmod 700 \"$tmp\"\n"
            + "mv -f \"$tmp\" \"$target\"\n"
            + "for old in \"${target%/*}\"/magicdesk-pty-*; do\n"
            + "  [ \"$old\" = \"$target\" ] || rm -f -- \"$old\"\n"
            + "done\n"
            + "trap - EXIT HUP INT TERM\n"
            + "exec \"$target\" --socket \"$1\" \"$2\" \"$3\" "
            + "\"$4\" \"$5\" "
            + "\"${SHELL:-/data/data/com.termux/files/usr/bin/bash}\" "
            + "\"/data/data/com.termux/files/usr/bin/bash\" "
            + "\"$6\"";

    private TermuxIntegration() {
    }

    static boolean isInstalled(final Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    static boolean isAvailable(final Context context) {
        return isInstalled(context)
                && context.checkSelfPermission(RUN_COMMAND_PERMISSION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isAutoLaunchBlocked(final Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            final String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(java.util.Locale.ROOT)
                            .contains("blocked by autolaunch")) {
                return true;
            }
            final Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return false;
    }

    static boolean ensureRunCommandPermission(final Activity activity) {
        if (activity.checkSelfPermission(RUN_COMMAND_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        activity.requestPermissions(
                new String[]{RUN_COMMAND_PERMISSION},
                PERMISSION_REQUEST_CODE);
        return false;
    }

    @SuppressLint("SdCardPath")
    static void runBackgroundShellCommand(
            final Activity activity,
            final String command,
            final String label,
            final String workingDirectory) {
        activity.startForegroundService(commandIntent(
                command, label, workingDirectory)
                .putExtra(EXTRA_BACKGROUND, true));
    }

    static void runBackgroundShellCommandForResult(
            final Context context,
            final String command,
            final String label,
            final String workingDirectory,
            final long timeoutMillis,
            final ResultCallback callback) {
        final TermuxCommandResultReceiver.Registration registration =
                TermuxCommandResultReceiver.register(
                        context, timeoutMillis, callback);
        try {
            context.startForegroundService(commandIntent(
                    command, label, workingDirectory)
                    .putExtra(EXTRA_BACKGROUND, true)
                    .putExtra(
                            EXTRA_RESULT_PENDING_INTENT,
                            registration.pendingIntent));
        } catch (RuntimeException error) {
            TermuxCommandResultReceiver.cancel(registration);
            throw error;
        }
    }

    static void runPtyBridge(
            final Context context,
            final int port,
            final String token,
            final int rows,
            final int columns,
            final String workingDirectory,
            final String startupCommand,
            final String target,
            final String encodedHelper) {
        final Intent intent = new Intent(ACTION_RUN_COMMAND)
                .setComponent(new ComponentName(
                        PACKAGE_NAME, RUN_COMMAND_SERVICE))
                .putExtra(
                        EXTRA_COMMAND_PATH,
                        "/data/data/com.termux/files/usr/bin/bash")
                .putExtra(EXTRA_ARGUMENTS, new String[]{
                        "-lc",
                        PTY_BOOTSTRAP,
                        "magicdesk-termux-pty",
                        Integer.toString(port),
                        token,
                        Integer.toString(rows),
                        Integer.toString(columns),
                        DesktopExecWorkingDirectory.normalize(workingDirectory),
                        DesktopExecCommand.normalize(startupCommand),
                        target
                })
                .putExtra(EXTRA_STDIN, encodedHelper)
                // The bridge handles the requested cwd after it starts, so
                // an inaccessible shared path cannot prevent the PTY itself.
                .putExtra(EXTRA_WORKDIR, HOME_DIRECTORY)
                .putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)
                .putExtra(EXTRA_BACKGROUND, true)
                .putExtra(EXTRA_COMMAND_LABEL, "MagicDesk embedded terminal");
        context.startForegroundService(intent);
    }

    @SuppressLint("SdCardPath")
    private static Intent commandIntent(
            final String command,
            final String label,
            final String workingDirectory) {
        final String directory = workingDirectory == null
                || workingDirectory.isEmpty()
                ? HOME_DIRECTORY
                : DesktopExecWorkingDirectory.normalize(workingDirectory);
        return new Intent(ACTION_RUN_COMMAND)
                .setComponent(new ComponentName(
                        PACKAGE_NAME, RUN_COMMAND_SERVICE))
                .putExtra(
                        EXTRA_COMMAND_PATH,
                        "/data/data/com.termux/files/usr/bin/bash")
                .putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", command})
                .putExtra(
                        EXTRA_WORKDIR,
                        directory)
                .putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)
                .putExtra(EXTRA_COMMAND_LABEL, label);
    }

    interface ResultCallback {
        void onResult(CommandResult result, Throwable error);
    }

    static final class CommandResult {
        final int exitCode;
        final int errorCode;
        final String stdout;
        final String stderr;
        final String errorMessage;

        CommandResult(
                final int exitCode,
                final int errorCode,
                final String stdout,
                final String stderr,
                final String errorMessage) {
            this.exitCode = exitCode;
            this.errorCode = errorCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }

        static CommandResult fromBundle(final Bundle result) {
            if (result == null) {
                return new CommandResult(
                        -1, -1, "", "", "missing Termux result bundle");
            }
            return new CommandResult(
                    result.getInt("exitCode", -1),
                    result.getInt("err", 0),
                    result.getString("stdout", ""),
                    result.getString("stderr", ""),
                    result.getString("errmsg", ""));
        }

        boolean success() {
            // Termux uses Activity.RESULT_OK (-1) as its plugin errno success
            // value; non-negative errno values describe integration failures.
            return exitCode == 0 && errorCode == Activity.RESULT_OK;
        }

        String usefulMessage() {
            if (!errorMessage.trim().isEmpty()) {
                return errorMessage.trim();
            }
            if (!stderr.trim().isEmpty()) {
                return stderr.trim();
            }
            return errorCode != Activity.RESULT_OK
                    ? "Termux command error " + errorCode
                    : "command exited " + exitCode;
        }
    }

}
