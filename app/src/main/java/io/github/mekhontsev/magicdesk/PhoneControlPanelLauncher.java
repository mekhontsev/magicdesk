package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PhoneControlPanelLauncher {
    private static final String TAG = "MagicDeskPhonePanel";
    private static final String AM = "/system/bin/am";
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread =
                        new Thread(runnable, "MagicDeskPhonePanel");
                thread.setDaemon(true);
                return thread;
            });

    private PhoneControlPanelLauncher() {
    }

    static void open(final Activity source) {
        final Display display = source.getDisplay();
        final boolean crossDisplay = display != null
                && display.getDisplayId() != Display.DEFAULT_DISPLAY;
        final Context context = source.getApplicationContext();
        if (crossDisplay && ShellAccess.isReady()) {
            EXECUTOR.execute(() -> {
                if (!openOnPhoneWithShell()) {
                    context.getMainExecutor().execute(
                            () -> openWithAndroidApi(context));
                }
            });
            return;
        }
        openWithAndroidApi(context);
    }

    static String createLaunchCommand(
            final String packageName,
            final String className) {
        final String componentClass = className.startsWith(packageName + ".")
                ? className.substring(packageName.length()) : className;
        return AM + " start --user 0 --display "
                + Display.DEFAULT_DISPLAY
                + " --activity-clear-top --activity-single-top -n "
                + packageName + "/" + componentClass;
    }

    static boolean openOnPhoneWithShell() {
        final String packageName =
                MagicDeskApplication.applicationContext().getPackageName();
        final String command = createLaunchCommand(
                packageName, ControlActivity.class.getName());
        try {
            final String output = ShellAccess.run(command);
            if (commandFailed(output)) {
                throw new IOException(output.trim());
            }
            return true;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "phone panel launch after Mirror failed", error);
            CompatibilityDiagnostics.record(
                    "DISPLAY-MODE-003",
                    "Could not open the MagicDesk phone control panel",
                    "shell=" + ShellAccess.statusLabel(),
                    error);
            return false;
        }
    }

    static boolean commandFailed(final String output) {
        if (output == null) {
            return false;
        }
        return output.contains("Error:")
                || output.contains("Permission Denial")
                || output.contains("Exception");
    }

    private static void openWithAndroidApi(final Context context) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        final Intent intent = ControlActivity.createLaunchIntent(context);
        context.startActivity(intent, options.toBundle());
    }
}
