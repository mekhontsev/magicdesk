package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConsoleSessionController {
    private static final String TAG = "MagicDeskConsoleSession";
    private static final String AM = "/system/bin/am";
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final String SURFACE_FLINGER_OPTION_COMMAND =
            "io.github.mekhontsev.magicdesk.SurfaceFlingerOptionCommand";
    private static final Pattern DESKTOP_HOME_TASK_ID_PATTERN =
            Pattern.compile("desktop-home-task-id=(\\d+)");

    private ConsoleSessionController() {
    }

    static void showWithRoot() {
        int displayId = ConsoleDisplayController.getActiveConsoleDisplayId();
        boolean startedConsoleMode = false;
        if (displayId <= 0) {
            final int externalDisplayId =
                    ConsoleDisplayController.findExternalDisplayId();
            if (externalDisplayId <= 0) {
                Log.w(TAG,
                        "cannot start Console mode: no physical external display");
                CompatibilityDiagnostics.record(
                        "NUBIA-CONSOLE-001",
                        "Cannot start Console mode",
                        "No physical external display was reported");
                return;
            }
            Log.i(TAG, "request Console mode on physical display="
                    + externalDisplayId);
            if (!ConsoleDisplayController.requestConsoleMode(
                    externalDisplayId)) {
                return;
            }
            displayId = ConsoleDisplayController.waitForConsoleDisplay();
            if (displayId <= 0) {
                Log.w(TAG,
                        "Console mode did not create an app mirror display");
                CompatibilityDiagnostics.record(
                        "NUBIA-CONSOLE-002",
                        "Console mode did not start",
                        "The firmware did not create app_mirror_displayid within "
                                + ConsoleDisplayController.START_TIMEOUT_MS
                                + " ms");
                return;
            }
            startedConsoleMode = true;
        }

        ConsoleDisplayController.ensureLandscape(displayId);
        final boolean desktopReady =
                DesktopRuntimeBridge.isDesktopReadyOnDisplay(displayId);
        final boolean desktopTaskReady =
                desktopReady && hasDesktopHomeTask(displayId);
        if (!startedConsoleMode && !desktopTaskReady) {
            setExternalTaskCaptionsEnabled(true);
        }
        final Boolean visibleTaskSnapshot =
                DesktopTaskController.hasVisibleAppTaskSnapshot(displayId);
        final boolean restoreWindows = !(visibleTaskSnapshot != null
                ? visibleTaskSnapshot.booleanValue()
                : hasVisibleAppTask(displayId));
        if (!desktopTaskReady && !startedConsoleMode) {
            final String preparedTask =
                    ConsoleModeSwitcher.runRootCommand(
                            appProcessCommand(TASK_CONTROL_COMMAND)
                                    + " prepare-desktop " + displayId).trim();
            Log.i(TAG, "prepared MagicDesk task: "
                    + preparedTask.replace('\n', ' '));
        }
        Log.i(TAG, "show MagicDesk display=" + displayId
                + " restoreWindows=" + restoreWindows
                + " cachedVisibility=" + (visibleTaskSnapshot != null)
                + " desktopReady=" + desktopReady
                + " desktopTaskReady=" + desktopTaskReady);
        final boolean newDesktopTask = !desktopTaskReady;
        final String launchTaskFlags = newDesktopTask
                ? " -f 0x18000000"
                : " --activity-reorder-to-front --activity-single-top";
        // The migrated task grants access to Nubia's private Console display
        // while the dedicated HOME task is being bootstrapped.
        final String launchComponent = newDesktopTask
                ? "io.github.mekhontsev.magicdesk/.DeviceSetupActivity"
                : "io.github.mekhontsev.magicdesk/.DesktopActivity";
        final String launchOutput = ConsoleModeSwitcher.runRootCommand(
                AM + " start -W --display " + displayId
                        + " --windowingMode 1"
                        + " --activityType 2"
                        + launchTaskFlags
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + (restoreWindows
                                ? " --es " + DesktopShellActivity.EXTRA_ACTION + " "
                                        + DesktopShellActivity.ACTION_RESTORE_WINDOWS
                                : "")
                        + " -n " + launchComponent).trim();
        Log.i(TAG, "MagicDesk launch output="
                + launchOutput.replace('\n', ' '));
        if (startedConsoleMode) {
            if (!waitForDesktopReady(displayId)) {
                Log.w(TAG,
                        "new Console desktop task did not become ready display="
                                + displayId);
                return;
            }
            NubiaTouchpadController.refreshOrOpen();
        }
    }

    static void showWithShizuku(final int displayId) {
        if (displayId <= 0) {
            Log.w(TAG,
                    "cannot show MagicDesk with Shizuku: Console Mode is inactive");
            CompatibilityDiagnostics.record(
                    "SHIZUKU-CONSOLE-001",
                    "Cannot open MagicDesk on the external display",
                    "Start Nubia Console Mode before using the MagicDesk notification");
            return;
        }
        try {
            ConsoleDisplayController.ensureLandscapeWithShizuku(displayId);
            final Boolean visibleTaskSnapshot =
                    DesktopTaskController.hasVisibleAppTaskSnapshot(displayId);
            final boolean restoreWindows =
                    visibleTaskSnapshot != null
                            && !visibleTaskSnapshot.booleanValue();
            final int desktopTaskId =
                    findDesktopHomeTaskWithShizuku(displayId);
            if (desktopTaskId >= 0) {
                final String focusOutput = PrivilegedCommandRunner.run(
                        AM + " task focus " + desktopTaskId).trim();
                Log.i(TAG, "Shizuku MagicDesk focus task=" + desktopTaskId
                        + " output=" + focusOutput.replace('\n', ' '));
                if (restoreWindows) {
                    DesktopRuntimeBridge.restoreLastVisibleWindows();
                }
                return;
            }
            final String output = PrivilegedCommandRunner.run(
                    AM + " start -W --display " + displayId
                            + " --windowingMode 1"
                            + " --activityType 2"
                            + " -f 0x18000000"
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.LAUNCHER"
                            + (restoreWindows
                                    ? " --es " + DesktopShellActivity.EXTRA_ACTION
                                            + " "
                                            + DesktopShellActivity.ACTION_RESTORE_WINDOWS
                                    : "")
                            + " -n io.github.mekhontsev.magicdesk/.DeviceSetupActivity")
                    .trim();
            if (output.startsWith("Error:")
                    || output.contains(
                            "Exception occurred while executing")) {
                throw new IOException(output);
            }
            Log.i(TAG, "Shizuku MagicDesk launch display=" + displayId
                    + " output=" + output.replace('\n', ' '));
        } catch (IOException error) {
            Log.w(TAG, "Shizuku MagicDesk launch failed", error);
            CompatibilityDiagnostics.record(
                    "SHIZUKU-CONSOLE-002",
                    "Could not open MagicDesk on the Console display",
                    error.getMessage());
        }
    }

    static boolean setExternalTaskCaptionsEnabled(final boolean enabled) {
        final String operation =
                enabled ? "enable-captions" : "restore-privacy";
        final String output = ConsoleModeSwitcher.runRootCommand(
                appProcessCommand(SURFACE_FLINGER_OPTION_COMMAND)
                        + " " + operation).trim();
        final String expected = "external-task-captions="
                + (enabled ? "enabled" : "restored");
        final boolean success = output.contains(expected);
        if (success) {
            Log.i(TAG, output.replace('\n', ' '));
        } else {
            Log.w(TAG,
                    "cannot update external task caption policy output="
                            + output);
        }
        return success;
    }

    private static int findDesktopHomeTaskWithShizuku(final int displayId)
            throws IOException {
        final String output = PrivilegedCommandRunner.run(
                appProcessCommand(TASK_CONTROL_COMMAND)
                        + " desktop-home-task-id " + displayId);
        final Matcher matcher =
                DESKTOP_HOME_TASK_ID_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException(
                    "could not query MagicDesk HOME task: " + output.trim());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean waitForDesktopReady(final int displayId) {
        final long deadline = SystemClock.uptimeMillis()
                + ConsoleDisplayController.START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!ConsoleDisplayController.displayExists(displayId)) {
                return false;
            }
            final String output = ConsoleModeSwitcher.runRootCommand(
                    appProcessCommand(TASK_CONTROL_COMMAND)
                            + " has-desktop-home " + displayId).trim();
            if (output.contains("desktop-home-task=true")) {
                Log.i(TAG,
                        "Console desktop task ready display=" + displayId);
                return true;
            }
            if (!output.contains("desktop-home-task=false")) {
                Log.w(TAG,
                        "cannot query Console desktop task output=" + output);
            }
            SystemClock.sleep(ConsoleDisplayController.STATE_POLL_MS);
        }
        return false;
    }

    private static boolean hasVisibleAppTask(final int displayId) {
        final String output = ConsoleModeSwitcher.runRootCommand(
                appProcessCommand(TASK_CONTROL_COMMAND)
                        + " has-visible-app " + displayId).trim();
        if (output.contains("visible-app-task=true")) {
            return true;
        }
        if (!output.contains("visible-app-task=false")) {
            Log.w(TAG, "cannot query visible app task output=" + output);
            return true;
        }
        return false;
    }

    private static boolean hasDesktopHomeTask(final int displayId) {
        final String output = ConsoleModeSwitcher.runRootCommand(
                appProcessCommand(TASK_CONTROL_COMMAND)
                        + " has-desktop-home " + displayId).trim();
        if (output.contains("desktop-home-task=true")) {
            return true;
        }
        if (!output.contains("desktop-home-task=false")) {
            Log.w(TAG,
                    "cannot query Console desktop task output=" + output);
        }
        return false;
    }

    private static String appProcessCommand(final String mainClass) {
        return "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                + "CLASSPATH=\"$APK\" /system/bin/app_process / " + mainClass;
    }
}
