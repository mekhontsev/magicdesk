package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
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
        if (crossDisplay && ShellAccess.isReady()) {
            final String command = createLaunchCommand(
                    source.getPackageName(),
                    ControlActivity.class.getName());
            EXECUTOR.execute(() -> {
                try {
                    final String output =
                            ShellAccess.run(command);
                    if (commandFailed(output)) {
                        throw new IOException(output.trim());
                    }
                } catch (IOException | RuntimeException error) {
                    Log.w(TAG, "privileged phone panel launch failed", error);
                    CompatibilityDiagnostics.record(
                            "NUBIA-DISPLAY-003",
                            "Could not open the MagicDesk phone control panel",
                            "shell=" + ShellAccess.statusLabel(),
                            error);
                    source.runOnUiThread(() -> openWithAndroidApi(source));
                }
            });
            return;
        }
        openWithAndroidApi(source);
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

    static boolean commandFailed(final String output) {
        if (output == null) {
            return false;
        }
        return output.contains("Error:")
                || output.contains("Permission Denial")
                || output.contains("Exception");
    }

    private static void openWithAndroidApi(final Activity source) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        final Intent intent = ControlActivity.createLaunchIntent(source);
        source.startActivity(intent, options.toBundle());
    }
}
