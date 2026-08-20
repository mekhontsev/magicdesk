package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.IOException;

final class TermuxIntegration {
    static final String PACKAGE_NAME = "com.termux";
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
    private static final String EXTRA_SESSION_ACTION =
            "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String EXTRA_SHELL_NAME =
            "com.termux.RUN_COMMAND_SHELL_NAME";
    private static final String EXTRA_SHELL_CREATE_MODE =
            "com.termux.RUN_COMMAND_SHELL_CREATE_MODE";
    private static final String EXTRA_COMMAND_LABEL =
            "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String SHELL_CREATE_MODE_REUSE_NAMED =
            "no-shell-with-name";
    private static final String SHELL_NAME_PREFIX = "MagicDesk: ";

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
            final String label) {
        activity.startForegroundService(commandIntent(command, label)
                .putExtra(EXTRA_BACKGROUND, true));
    }

    static void runForegroundShellCommand(
            final Activity activity,
            final String command,
            final String label) {
        final String shellName = SHELL_NAME_PREFIX + label + " ["
                + Integer.toHexString(command.hashCode()) + "]";
        activity.startForegroundService(commandIntent(command, label)
                .putExtra(EXTRA_BACKGROUND, false)
                // Select the session but let MagicDesk place the Termux task.
                .putExtra(EXTRA_SESSION_ACTION, "2")
                .putExtra(EXTRA_SHELL_NAME, shellName)
                .putExtra(
                        EXTRA_SHELL_CREATE_MODE,
                        SHELL_CREATE_MODE_REUSE_NAMED));
    }

    @SuppressLint("SdCardPath")
    private static Intent commandIntent(
            final String command,
            final String label) {
        return new Intent(ACTION_RUN_COMMAND)
                .setComponent(new ComponentName(
                        PACKAGE_NAME, RUN_COMMAND_SERVICE))
                .putExtra(
                        EXTRA_COMMAND_PATH,
                        "/data/data/com.termux/files/usr/bin/bash")
                .putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", command})
                .putExtra(
                        EXTRA_WORKDIR,
                        "/data/data/com.termux/files/home")
                .putExtra(EXTRA_COMMAND_LABEL, label);
    }

    @SuppressLint("SdCardPath")
    static boolean openDirectory(
            final Activity activity, final String absolutePath) {
        if (!ensureRunCommandPermission(activity)) {
            return false;
        }
        final String directory =
                ShellFilePathPolicy.normalizeShellAbsolute(absolutePath);
        final String shellName = shellNameForDirectory(directory);
        final Intent intent = new Intent(ACTION_RUN_COMMAND)
                .setComponent(new ComponentName(
                        PACKAGE_NAME, RUN_COMMAND_SERVICE))
                // RUN_COMMAND executes inside Termux; this is Termux's
                // documented prefix, not MagicDesk private storage.
                .putExtra(EXTRA_COMMAND_PATH,
                        "/data/data/com.termux/files/usr/bin/bash")
                .putExtra(EXTRA_WORKDIR, directory)
                .putExtra(EXTRA_BACKGROUND, false)
                // Reopen the terminal previously created for this directory;
                // Termux creates it atomically when it no longer exists.
                .putExtra(EXTRA_SHELL_NAME, shellName)
                .putExtra(
                        EXTRA_SHELL_CREATE_MODE,
                        SHELL_CREATE_MODE_REUSE_NAMED)
                // Select the requested session without letting Termux open its
                // activity on Android's default display. MagicDesk opens the
                // activity on the Files window's display immediately after.
                .putExtra(EXTRA_SESSION_ACTION, "2")
                .putExtra(EXTRA_COMMAND_LABEL, "MagicDesk Files");
        // RunCommandService promotes itself immediately. Starting it as a
        // background service is rejected by current Android releases.
        activity.startForegroundService(intent);
        return true;
    }

    static String shellNameForDirectory(final String absolutePath) {
        return SHELL_NAME_PREFIX
                + ShellFilePathPolicy.normalizeShellAbsolute(absolutePath);
    }

    static void showOnDisplay(
            final Activity activity, final int displayId) throws IOException {
        final AppLaunchTarget target = AppLaunchTarget.packageDefault(
                PACKAGE_NAME);
        final Intent launchIntent = target.resolve(
                activity.getPackageManager());
        if (launchIntent == null) {
            throw new IOException("Termux launcher activity is unavailable");
        }
        WindowedAppLauncher.launch(
                launchIntent,
                target,
                displayId,
                null,
                true,
                null,
                WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                null);
    }
}
