package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

final class TermuxIntegration {
    static final String RUN_COMMAND_PERMISSION =
            "com.termux.permission.RUN_COMMAND";
    static final int PERMISSION_REQUEST_CODE = 7312;

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
    static final String PTY_BOOTSTRAP =
            "set -eu\n"
            + "target=\"${HOME:?}/.local/libexec/$7\"\n"
            + "mkdir -p \"${target%/*}\"\n"
            + "tmp=\"${target%/*}/.magicdesk-pty-tmp.$$\"\n"
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
            + "\"${SHELL:-${PREFIX:?}/bin/bash}\" "
            + "\"${PREFIX:?}/bin/bash\" "
            + "\"$6\"";

    private TermuxIntegration() {
    }

    static boolean isInstalled(final Context context) {
        try {
            context.getPackageManager().getPackageInfo(IntegrationPackage.TERMUX.selected(), 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    static boolean isAvailable(final Context context) {
        return inspect(context).available();
    }

    static String homeDirectory(final Context context) {
        final Endpoint endpoint = inspect(context);
        if (!endpoint.installed) { throw new IllegalStateException(endpoint.packageName + ": " + endpoint.error); }
        return endpoint.homeDirectory;
    }

    static Endpoint inspect(final Context context) {
        final String selected = IntegrationPackage.TERMUX.selected();
        final PackageManager packages = context.getPackageManager();
        final android.content.pm.ApplicationInfo app;
        try {
            app = packages.getApplicationInfo(selected, PackageManager.ApplicationInfoFlags.of(0));
        } catch (PackageManager.NameNotFoundException error) {
            return new Endpoint(selected, false, null, "", -1, false,
                    "Selected Termux package is not installed");
        }
        final String home = app.dataDir + "/files/home";
        final List<ResolveInfo> matches = packages.queryIntentServices(
                new Intent(ACTION_RUN_COMMAND).setPackage(selected),
                PackageManager.ResolveInfoFlags.of(0));
        if (matches.size() != 1) {
            return new Endpoint(selected, true, null, home, app.uid, false,
                    "Expected one compatible RUN_COMMAND service, found " + matches.size());
        }
        final ServiceInfo service = matches.get(0).serviceInfo;
        final String compatibilityError = serviceError(app.enabled && service.enabled,
                service.exported, service.permission);
        final boolean permissionRequired = compatibilityError.isEmpty()
                && RUN_COMMAND_PERMISSION.equals(service.permission)
                && context.checkSelfPermission(RUN_COMMAND_PERMISSION) != PackageManager.PERMISSION_GRANTED;
        return new Endpoint(selected, true, new ComponentName(selected, service.name), home, app.uid,
                permissionRequired, permissionRequired
                        ? "Termux RUN_COMMAND permission is not granted" : compatibilityError);
    }

    static String serviceError(final boolean enabled, final boolean exported,
            final String permission) {
        if (!enabled || !exported) { return "RUN_COMMAND service is disabled or not exported"; }
        if (permission != null && !permission.isEmpty() && !RUN_COMMAND_PERMISSION.equals(permission)) {
            return "Unsupported RUN_COMMAND permission: " + permission;
        }
        return "";
    }

    /** One resolved recipient; in-flight commands never reread the user's selection. */
    static final class Endpoint {
        final String packageName;
        final boolean installed;
        final ComponentName service;
        final String homeDirectory;
        final String error;
        final int uid;
        final boolean permissionRequired;

        Endpoint(final String packageName, final boolean installed, final ComponentName service,
                final String homeDirectory, final int uid, final boolean permissionRequired, final String error) {
            this.packageName = packageName;
            this.installed = installed;
            this.service = service;
            this.homeDirectory = homeDirectory;
            this.error = error;
            this.uid = uid;
            this.permissionRequired = permissionRequired;
        }

        boolean available() { return error.isEmpty(); }

        void requireAvailable() {
            if (!available()) { throw new IllegalStateException(packageName + ": " + error); }
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("package", packageName).put("installed", installed)
                    .put("available", available()).put("homeDirectory", homeDirectory)
                    .put("uid", uid).put("permissionRequired", permissionRequired)
                    .put("service", service == null ? JSONObject.NULL : service.flattenToString())
                    .put("error", error);
        }
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
        final Endpoint endpoint = inspect(activity);
        if (endpoint.available()) { return true; }
        if (!endpoint.permissionRequired) {
            android.widget.Toast.makeText(activity, endpoint.packageName + ": " + endpoint.error,
                    android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        activity.requestPermissions(
                new String[]{RUN_COMMAND_PERMISSION},
                PERMISSION_REQUEST_CODE);
        return false;
    }

    static void runBackgroundShellCommand(
            final Activity activity,
            final String command,
            final String label,
            final String workingDirectory) {
        activity.startForegroundService(commandIntent(inspect(activity),
                command, label, workingDirectory)
                .putExtra(EXTRA_BACKGROUND, true));
    }

    static void runBackgroundShellCommandForResult(
            final Context context,
            final Endpoint endpoint,
            final String command,
            final String label,
            final String workingDirectory,
            final long timeoutMillis,
            final ResultCallback callback) {
        final TermuxCommandResultReceiver.Registration registration =
                TermuxCommandResultReceiver.register(
                        context, timeoutMillis, callback);
        try {
            context.startForegroundService(commandIntent(endpoint,
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
            final Endpoint endpoint,
            final int port,
            final String token,
            final int rows,
            final int columns,
            final String workingDirectory,
            final String startupCommand,
            final String target,
            final String encodedHelper) {
        endpoint.requireAvailable();
        final Intent intent = new Intent(ACTION_RUN_COMMAND)
                .setComponent(endpoint.service)
                .putExtra(
                        EXTRA_COMMAND_PATH,
                        "$PREFIX/bin/bash")
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
                // Install from Termux's home. The bridge validates the requested
                // cwd before launching a shell or publishing a ready PTY.
                .putExtra(EXTRA_WORKDIR, "~/")
                .putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)
                .putExtra(EXTRA_BACKGROUND, true)
                .putExtra(EXTRA_COMMAND_LABEL, "MagicDesk embedded terminal");
        context.startForegroundService(intent);
    }

    private static Intent commandIntent(
            final Endpoint endpoint,
            final String command,
            final String label,
            final String workingDirectory) {
        endpoint.requireAvailable();
        final String directory = workingDirectory == null
                || workingDirectory.isEmpty()
                ? "~/"
                : DesktopExecWorkingDirectory.normalize(workingDirectory);
        return new Intent(ACTION_RUN_COMMAND)
                .setComponent(endpoint.service)
                .putExtra(
                        EXTRA_COMMAND_PATH,
                        "$PREFIX/bin/bash")
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
