package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConsoleSessionController {
    private static final String TAG = "MagicDeskConsoleSession";
    private static final String AM = "/system/bin/am";
    private static final String SEED_COMPONENT =
            "io.github.mekhontsev.magicdesk/.ConsoleSeedActivity";
    private static final String DESKTOP_COMPONENT =
            "io.github.mekhontsev.magicdesk/.DesktopActivity";
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final Pattern DESKTOP_TASK_ID_PATTERN =
            Pattern.compile("desktop-task-id=(-?\\d+)");

    private ConsoleSessionController() {
    }

    static void show(final int displayId) {
        int consoleDisplayId = displayId > 0
                ? displayId : ConsoleDisplayController.getActiveConsoleDisplayId();
        boolean startedConsoleMode = false;
        boolean seedStarted = false;
        int physicalDisplayId = -1;
        try {
            if (consoleDisplayId <= 0) {
                physicalDisplayId =
                        ConsoleDisplayController.findExternalDisplayId();
                if (physicalDisplayId <= 0) {
                    throw new IOException(
                            "no physical external display was reported");
                }
                if (ConsoleDisplayController.isMirrorMode()
                        && !hasVisibleAppTask(0)) {
                    seedStarted = startConsoleSeedTask();
                    if (!seedStarted) {
                        return;
                    }
                }
                if (!ConsoleDisplayController.requestConsoleMode(
                        physicalDisplayId)) {
                    return;
                }
                consoleDisplayId =
                        ConsoleDisplayController.waitForConsoleDisplay();
                if (consoleDisplayId <= 0) {
                    throw new IOException(
                            "Nubia Console Mode did not create an app mirror display");
                }
                startedConsoleMode = true;
            }
            ConsoleDisplayController.ensureLandscape(consoleDisplayId);
            if (startedConsoleMode) {
                prepareConsoleDisplayDensity(
                        consoleDisplayId, physicalDisplayId);
            }
            final Boolean visibleTaskSnapshot =
                    DesktopTaskController.hasVisibleAppTaskSnapshot(
                            consoleDisplayId);
            final boolean restoreWindows =
                    visibleTaskSnapshot != null
                            && !visibleTaskSnapshot.booleanValue();
            final int desktopTaskId =
                    findDesktopTask(consoleDisplayId);
            if (desktopTaskId >= 0) {
                final String focusOutput = ShellAccess.run(
                        AM + " task focus " + desktopTaskId).trim();
                Log.i(TAG, "Shell MagicDesk focus task=" + desktopTaskId
                        + " output=" + focusOutput.replace('\n', ' '));
                if (restoreWindows) {
                    DesktopRuntimeBridge.restoreLastVisibleWindows();
                }
                if (startedConsoleMode) {
                    NubiaTouchpadController.refreshOrOpen();
                }
                return;
            }
            final String output = ShellAccess.run(
                    AM + " start -W --display " + consoleDisplayId
                            + " --windowingMode 5"
                            + " -f 0x18000000"
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.LAUNCHER"
                            + (restoreWindows
                                    ? " --es " + DesktopShellActivity.EXTRA_ACTION
                                            + " "
                                            + DesktopShellActivity.ACTION_RESTORE_WINDOWS
                                    : "")
                            + " -n " + DESKTOP_COMPONENT)
                    .trim();
            if (output.startsWith("Error:")
                    || output.contains(
                            "Exception occurred while executing")) {
                throw new IOException(output);
            }
            Log.i(TAG, "Shell MagicDesk launch display=" + consoleDisplayId
                    + " output=" + output.replace('\n', ' '));
            if (startedConsoleMode
                    && waitForDesktopReady(consoleDisplayId)) {
                NubiaTouchpadController.refreshOrOpen();
            }
        } catch (IOException error) {
            Log.w(TAG, "Shell MagicDesk launch failed", error);
            CompatibilityDiagnostics.record(
                    "SHELL-CONSOLE-002",
                    "Could not open MagicDesk on the Console display",
                    error.getMessage());
        } finally {
            finishConsoleSeedTask(seedStarted);
        }
    }

    static boolean setExternalTaskCaptionsEnabled(final boolean enabled) {
        if (!ShellAccess.isReady()) {
            return true;
        }
        return NubiaCaptionVisibilityManager.setEnabled(enabled);
    }

    private static int findDesktopTask(final int displayId)
            throws IOException {
        final String output = ShellAccess.run(
                AppProcessCommand.run(
                        TASK_CONTROL_COMMAND,
                        "desktop-task-id " + displayId));
        final Matcher matcher =
                DESKTOP_TASK_ID_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException(
                    "could not query MagicDesk desktop task: " + output.trim());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean hasVisibleAppTask(final int displayId)
            throws IOException {
        final String output = ShellAccess.run(
                AppProcessCommand.run(
                        TASK_CONTROL_COMMAND,
                        "has-visible-app " + displayId)).trim();
        if (output.contains("visible-app-task=true")) {
            return true;
        }
        if (output.contains("visible-app-task=false")) {
            return false;
        }
        throw new IOException(
                "could not query visible tasks: " + output);
    }

    private static boolean startConsoleSeedTask()
            throws IOException {
        final String output = ShellAccess.run(
                AM + " start -W --display 0"
                        + " --windowingMode 1"
                        + " --activity-reorder-to-front"
                        + " --activity-single-top"
                        + " -n " + SEED_COMPONENT).trim();
        if (output.contains("Status: ok")) {
            Log.i(TAG, "prepared Console seed task");
            return true;
        }
        CompatibilityDiagnostics.record(
                "NUBIA-CONSOLE-004",
                "The external desktop needs a foreground application",
                output);
        return false;
    }

    private static boolean waitForDesktopReady(
            final int displayId) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + ConsoleDisplayController.START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!ConsoleDisplayController.displayExists(displayId)) {
                return false;
            }
            if (findDesktopTask(displayId) >= 0) {
                return true;
            }
            SystemClock.sleep(ConsoleDisplayController.STATE_POLL_MS);
        }
        return false;
    }

    private static void finishConsoleSeedTask(final boolean seedStarted) {
        if (seedStarted) {
            ConsoleSeedActivity.finishActive();
        }
    }

    private static void prepareConsoleDisplayDensity(
            final int displayId, final int physicalDisplayId) {
        try {
            final Integer dpi = DisplayProfileController.prepareExternalProfile(
                    MagicDeskApplication.applicationContext(),
                    physicalDisplayId);
            if (dpi != null) {
                ConsoleDisplayController.applyStartupDensity(
                        displayId, dpi.intValue());
            }
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Cannot prepare Console display profile", error);
        }
    }

}
