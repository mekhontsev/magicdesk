package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.widget.Toast;

/** Executes a Desktop Entry when Files is open outside a desktop session. */
final class StandaloneDesktopExecLauncher {
    private StandaloneDesktopExecLauncher() {
    }

    static boolean launch(
            final Activity activity,
            final DesktopApplicationShortcut shortcut,
            final int displayId) {
        if (shortcut == null || !shortcut.hasExecLaunch()) {
            return false;
        }
        if (isTermuxX11(shortcut)) {
            return launchTermuxX11(activity, shortcut, displayId);
        }
        if (shortcut.terminal) {
            return shortcut.execBackend == DesktopExecBackend.SHELL
                    ? openConsole(activity, shortcut, displayId)
                    : openTermux(activity, shortcut, displayId);
        }
        final DesktopExecRunner.StartResult result;
        try {
            result = DesktopExecRunner.runBackground(
                    activity,
                    shortcut.execBackend,
                    shortcut.exec,
                    shortcut.name,
                    (commandResult, error) -> {
                        if (error != null || commandResult != null
                                && commandResult.exitCode != 0) {
                            showFailure(activity, shortcut);
                        }
                    });
        } catch (RuntimeException error) {
            showFailure(activity, shortcut);
            return true;
        }
        if (result == DesktopExecRunner.StartResult.UNAVAILABLE) {
            showUnavailable(activity, shortcut);
        }
        return true;
    }

    private static boolean openConsole(
            final Activity activity,
            final DesktopApplicationShortcut shortcut,
            final int displayId) {
        try {
            activity.startActivity(
                    CommandConsoleActivity.createCommandIntent(
                            activity, shortcut.exec),
                    options(displayId).toBundle());
        } catch (RuntimeException error) {
            showFailure(activity, shortcut);
        }
        return true;
    }

    private static boolean openTermux(
            final Activity activity,
            final DesktopApplicationShortcut shortcut,
            final int displayId) {
        try {
            final DesktopExecRunner.StartResult result =
                    DesktopExecRunner.runTermuxForeground(
                            activity, shortcut.exec, shortcut.name);
            if (result == DesktopExecRunner.StartResult.UNAVAILABLE) {
                showUnavailable(activity, shortcut);
                return true;
            }
            if (result == DesktopExecRunner.StartResult.STARTED) {
                openPackage(activity, TermuxIntegration.PACKAGE_NAME, displayId);
            }
        } catch (RuntimeException error) {
            showFailure(activity, shortcut);
        }
        return true;
    }

    private static boolean launchTermuxX11(
            final Activity activity,
            final DesktopApplicationShortcut shortcut,
            final int displayId) {
        if (!TermuxX11Integration.isAvailable(activity)) {
            showUnavailable(activity, shortcut);
            return true;
        }
        if (!TermuxX11Integration.ensureRunCommandPermission(activity)) {
            return true;
        }
        try {
            openPackage(activity, TermuxX11Integration.PACKAGE_NAME, displayId);
            TermuxX11Integration.startOrReconnect(
                    activity, DesktopExecCommand.prepare(shortcut.exec));
        } catch (RuntimeException error) {
            showFailure(activity, shortcut);
        }
        return true;
    }

    private static boolean isTermuxX11(
            final DesktopApplicationShortcut shortcut) {
        return shortcut.launchTarget != null
                && TermuxX11Integration.PACKAGE_NAME.equals(
                        shortcut.launchTarget.packageName)
                && shortcut.execBackend == DesktopExecBackend.TERMUX;
    }

    private static void openPackage(
            final Activity activity,
            final String packageName,
            final int displayId) {
        final Intent intent = activity.getPackageManager()
                .getLaunchIntentForPackage(packageName);
        if (intent == null) {
            throw new IllegalStateException(
                    "launcher activity is unavailable: " + packageName);
        }
        activity.startActivity(intent, options(displayId).toBundle());
    }

    private static ActivityOptions options(final int displayId) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        return options;
    }

    private static void showUnavailable(
            final Activity activity,
            final DesktopApplicationShortcut shortcut) {
        Toast.makeText(
                activity,
                activity.getString(
                        R.string.status_desktop_exec_unavailable,
                        shortcut.name),
                Toast.LENGTH_LONG).show();
    }

    private static void showFailure(
            final Activity activity,
            final DesktopApplicationShortcut shortcut) {
        Toast.makeText(
                activity,
                activity.getString(
                        R.string.status_desktop_exec_failed,
                        shortcut.name),
                Toast.LENGTH_LONG).show();
    }
}
