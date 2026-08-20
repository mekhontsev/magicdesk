package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

final class TermuxX11Integration {
    static final String PACKAGE_NAME = "com.termux.x11";

    private TermuxX11Integration() {
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
        return TermuxIntegration.isInstalled(context) && isInstalled(context);
    }

    static boolean handlesDefaultLaunch(
            final Context context,
            final AppItem app) {
        return app != null
                && PACKAGE_NAME.equals(app.packageName)
                && isAvailable(context);
    }

    static boolean ensureRunCommandPermission(final Activity activity) {
        return TermuxIntegration.ensureRunCommandPermission(activity);
    }

    static void startOrReconnect(final Activity activity) {
        startOrReconnect(
                activity,
                MagicDeskSettings.load().termuxX11StartupCommand);
    }

    static void startOrReconnect(
            final Activity activity,
            final String command) {
        TermuxIntegration.runBackgroundShellCommand(
                activity,
                TermuxX11StartupCommand.startOrReconnect(command),
                "MagicDesk Termux:X11");
    }

    static boolean handlesExecLaunch(
            final AppItem app,
            final DesktopApplicationShortcut shortcut) {
        return app != null
                && PACKAGE_NAME.equals(app.packageName)
                && shortcut != null
                && shortcut.hasExecLaunch()
                && shortcut.execBackend == DesktopExecBackend.TERMUX;
    }

    static String diagnostics(final Context context) {
        final boolean termuxInstalled = TermuxIntegration.isInstalled(context);
        final boolean x11Installed = isInstalled(context);
        final boolean permission = context.checkSelfPermission(
                TermuxIntegration.RUN_COMMAND_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
        final String command = MagicDeskSettings.load()
                .termuxX11StartupCommand;
        return "termux=" + termuxInstalled
                + ", x11=" + x11Installed
                + ", runCommand=" + permission
                + ", startupCommand="
                + (TermuxX11StartupCommand.DEFAULT.equals(command)
                        ? "default" : "custom");
    }
}
