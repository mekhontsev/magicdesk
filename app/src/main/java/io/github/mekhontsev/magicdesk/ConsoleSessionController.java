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
    private static final String SURFACE_FLINGER_OPTION_COMMAND =
            "io.github.mekhontsev.magicdesk.SurfaceFlingerOptionCommand";
    private static final Pattern DESKTOP_HOME_TASK_ID_PATTERN =
            Pattern.compile("desktop-home-task-id=(-?\\d+)");

    private ConsoleSessionController() {
    }

    static void showWithRoot() {
        int displayId = ConsoleDisplayController.getActiveConsoleDisplayId();
        boolean startedConsoleMode = false;
        boolean seedStarted = false;
        int physicalDisplayId = -1;
        if (displayId <= 0) {
            final int externalDisplayId =
                    ConsoleDisplayController.findExternalDisplayId();
            if (externalDisplayId <= 0) {
                Log.w(TAG,
                        "cannot start Console mode: no physical external display");
                CompatibilityDiagnostics.record(
                        "NUBIA-CONSOLE-001",
                        "Cannot start the external desktop",
                        "No physical external display was reported");
                return;
            }
            physicalDisplayId = externalDisplayId;
            Log.i(TAG, "request Console mode on physical display="
                    + externalDisplayId);
            if (ConsoleDisplayController.isMirrorMode()
                    && !hasVisibleAppTask(0)) {
                seedStarted = startConsoleSeedTask();
                if (!seedStarted) {
                    return;
                }
            }
            if (!ConsoleDisplayController.requestConsoleMode(
                    externalDisplayId)) {
                finishConsoleSeedTask(seedStarted);
                return;
            }
            displayId = ConsoleDisplayController.waitForConsoleDisplay();
            if (displayId <= 0) {
                Log.w(TAG,
                        "Console mode did not create an app mirror display");
                CompatibilityDiagnostics.record(
                        "NUBIA-CONSOLE-002",
                        "The external desktop did not start",
                        "Nubia Console Mode did not create app_mirror_displayid within "
                                + ConsoleDisplayController.START_TIMEOUT_MS
                                + " ms");
                finishConsoleSeedTask(seedStarted);
                return;
            }
            startedConsoleMode = true;
        }

        ConsoleDisplayController.ensureLandscape(displayId);
        if (startedConsoleMode) {
            prepareConsoleDisplayDensity(displayId, physicalDisplayId);
        }
        final boolean desktopReady =
                DesktopRuntimeBridge.isDesktopReadyOnDisplay(displayId);
        final boolean desktopTaskReady =
                desktopReady && hasDesktopHomeTask(displayId);
        if (!startedConsoleMode && !desktopTaskReady) {
            setExternalTaskCaptionsEnabled(true);
        }
        final Boolean visibleTaskSnapshot = startedConsoleMode
                ? Boolean.FALSE
                : DesktopTaskController.hasVisibleAppTaskSnapshot(displayId);
        final boolean restoreWindows = startedConsoleMode
                || !(visibleTaskSnapshot != null
                        ? visibleTaskSnapshot.booleanValue()
                        : hasVisibleAppTask(displayId));
        if (!desktopTaskReady && !startedConsoleMode) {
            final String preparedTask =
                    ConsoleModeSwitcher.runRootCommand(
                            AppProcessCommand.run(
                                    TASK_CONTROL_COMMAND,
                                    "prepare-desktop " + displayId)).trim();
            Log.i(TAG, "prepared MagicDesk task: "
                    + preparedTask.replace('\n', ' '));
        }
        Log.i(TAG, "show MagicDesk display=" + displayId
                + " restoreWindows=" + restoreWindows
                + " cachedVisibility="
                + (!startedConsoleMode && visibleTaskSnapshot != null)
                + " desktopReady=" + desktopReady
                + " desktopTaskReady=" + desktopTaskReady);
        final boolean newDesktopTask = !desktopTaskReady;
        final String launchTaskFlags = newDesktopTask
                ? " -f 0x18000000"
                : " --activity-reorder-to-front --activity-single-top";
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
                        + " -n " + DESKTOP_COMPONENT).trim();
        Log.i(TAG, "MagicDesk launch output="
                + launchOutput.replace('\n', ' '));
        if (startedConsoleMode) {
            if (!waitForDesktopReady(displayId)) {
                Log.w(TAG,
                        "new Console desktop task did not become ready display="
                                + displayId);
                finishConsoleSeedTask(seedStarted);
                return;
            }
            finishConsoleSeedTask(seedStarted);
            NubiaTouchpadController.refreshOrOpen();
        }
    }

    static void showWithShizuku(final int displayId) {
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
                        && !hasVisibleAppTaskWithShizuku(0)) {
                    seedStarted = startConsoleSeedTaskWithShizuku();
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
            ConsoleDisplayController.ensureLandscapeWithShizuku(
                    consoleDisplayId);
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
                    findDesktopHomeTaskWithShizuku(consoleDisplayId);
            if (desktopTaskId >= 0) {
                final String focusOutput = PrivilegedCommandRunner.run(
                        AM + " task focus " + desktopTaskId).trim();
                Log.i(TAG, "Shizuku MagicDesk focus task=" + desktopTaskId
                        + " output=" + focusOutput.replace('\n', ' '));
                if (restoreWindows) {
                    DesktopRuntimeBridge.restoreLastVisibleWindows();
                }
                if (startedConsoleMode) {
                    NubiaTouchpadController.refreshOrOpen();
                }
                return;
            }
            final String output = PrivilegedCommandRunner.run(
                    AM + " start -W --display " + consoleDisplayId
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
                            + " -n " + DESKTOP_COMPONENT)
                    .trim();
            if (output.startsWith("Error:")
                    || output.contains(
                            "Exception occurred while executing")) {
                throw new IOException(output);
            }
            Log.i(TAG, "Shizuku MagicDesk launch display=" + consoleDisplayId
                    + " output=" + output.replace('\n', ' '));
            if (startedConsoleMode
                    && waitForDesktopReadyWithShizuku(consoleDisplayId)) {
                NubiaTouchpadController.refreshOrOpen();
            }
        } catch (IOException error) {
            Log.w(TAG, "Shizuku MagicDesk launch failed", error);
            CompatibilityDiagnostics.record(
                    "SHIZUKU-CONSOLE-002",
                    "Could not open MagicDesk on the Console display",
                    error.getMessage());
        } finally {
            finishConsoleSeedTask(seedStarted);
        }
    }

    static boolean setExternalTaskCaptionsEnabled(final boolean enabled) {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.EXTERNAL_CAPTION_VISIBILITY)) {
            return true;
        }
        if (!RuntimeAccess.allowsRootCommands()) {
            return NubiaCaptionVisibilityManager.setEnabled(enabled);
        }
        final String operation =
                enabled ? "enable-captions" : "restore-privacy";
        final String output = ConsoleModeSwitcher.runRootCommand(
                AppProcessCommand.run(
                        SURFACE_FLINGER_OPTION_COMMAND,
                        operation)).trim();
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
                AppProcessCommand.run(
                        TASK_CONTROL_COMMAND,
                        "desktop-home-task-id " + displayId));
        final Matcher matcher =
                DESKTOP_HOME_TASK_ID_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException(
                    "could not query MagicDesk HOME task: " + output.trim());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean hasVisibleAppTaskWithShizuku(final int displayId)
            throws IOException {
        final String output = PrivilegedCommandRunner.run(
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

    private static boolean startConsoleSeedTaskWithShizuku()
            throws IOException {
        final String output = PrivilegedCommandRunner.run(
                AM + " start -W --display 0"
                        + " --windowingMode 1"
                        + " --activity-reorder-to-front"
                        + " --activity-single-top"
                        + " -n " + SEED_COMPONENT).trim();
        if (output.contains("Status: ok")) {
            Log.i(TAG, "prepared Shizuku Console seed task");
            return true;
        }
        CompatibilityDiagnostics.record(
                "NUBIA-CONSOLE-004",
                "The external desktop needs a foreground application",
                output);
        return false;
    }

    private static boolean waitForDesktopReadyWithShizuku(
            final int displayId) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + ConsoleDisplayController.START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!ConsoleDisplayController.displayExists(displayId)) {
                return false;
            }
            if (findDesktopHomeTaskWithShizuku(displayId) >= 0) {
                return true;
            }
            SystemClock.sleep(ConsoleDisplayController.STATE_POLL_MS);
        }
        return false;
    }

    private static boolean waitForDesktopReady(final int displayId) {
        final long deadline = SystemClock.uptimeMillis()
                + ConsoleDisplayController.START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!ConsoleDisplayController.displayExists(displayId)) {
                return false;
            }
            final String output = ConsoleModeSwitcher.runRootCommand(
                    AppProcessCommand.run(
                            TASK_CONTROL_COMMAND,
                            "has-desktop-home " + displayId)).trim();
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
                AppProcessCommand.run(
                        TASK_CONTROL_COMMAND,
                        "has-visible-app " + displayId)).trim();
        if (output.contains("visible-app-task=true")) {
            return true;
        }
        if (!output.contains("visible-app-task=false")) {
            Log.w(TAG, "cannot query visible app task output=" + output);
            return true;
        }
        return false;
    }

    private static boolean startConsoleSeedTask() {
        final String output = ConsoleModeSwitcher.runRootCommand(
                AM + " start -W --display 0"
                        + " --windowingMode 1"
                        + " --activity-reorder-to-front"
                        + " --activity-single-top"
                        + " -n " + SEED_COMPONENT).trim();
        if (output.contains("Status: ok")) {
            Log.i(TAG, "prepared foreground Console seed task");
            return true;
        }
        Log.w(TAG, "cannot prepare foreground Console seed task output="
                + output.replace('\n', ' '));
        CompatibilityDiagnostics.record(
                "NUBIA-CONSOLE-004",
                "The external desktop needs a foreground application",
                output);
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

    private static boolean hasDesktopHomeTask(final int displayId) {
        final String output = ConsoleModeSwitcher.runRootCommand(
                AppProcessCommand.run(
                        TASK_CONTROL_COMMAND,
                        "has-desktop-home " + displayId)).trim();
        if (output.contains("desktop-home-task=true")) {
            return true;
        }
        if (!output.contains("desktop-home-task=false")) {
            Log.w(TAG,
                    "cannot query Console desktop task output=" + output);
        }
        return false;
    }

}
