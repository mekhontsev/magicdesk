package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;

final class ConsoleSessionController {
    private static final String TAG = "MagicDeskConsoleSession";
    private static final String SEED_COMPONENT =
            "io.github.mekhontsev.magicdesk/.ConsoleSeedActivity";
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final String AM = "/system/bin/am";

    private ConsoleSessionController() {
    }

    static void show(final int displayId) {
        int consoleDisplayId = displayId > 0
                ? displayId : ConsoleDisplayController.getActiveConsoleDisplayId();
        boolean startedConsoleMode = false;
        boolean seedStarted = false;
        int physicalDisplayId = -1;
        NubiaExternalDisplayModeController.PreparedMode preparedMode = null;
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
                try {
                    preparedMode = NubiaExternalDisplayModeController.prepare(
                            MagicDeskApplication.applicationContext(),
                            physicalDisplayId);
                    physicalDisplayId = preparedMode.physicalDisplayId();
                } catch (IOException | RuntimeException error) {
                    Log.w(TAG, "Cannot prepare Nubia external display mode", error);
                    CompatibilityDiagnostics.record(
                            "NUBIA-DISPLAY-001",
                            "Could not apply the external display launch settings",
                            error.getMessage(),
                            error);
                    return;
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
            final DesktopSessionController.ShowResult desktopResult =
                    DesktopSessionController.show(
                            DesktopDisplayTarget.wired(consoleDisplayId));
            if (startedConsoleMode && desktopResult.ready) {
                NubiaTouchpadController.refreshOrOpen();
            }
        } catch (IOException error) {
            Log.w(TAG, "Shell MagicDesk launch failed", error);
            CompatibilityDiagnostics.record(
                    "SHELL-CONSOLE-002",
                    "Could not open MagicDesk on the Console display",
                    error.getMessage());
        } finally {
            if (preparedMode != null) {
                preparedMode.close();
            }
            finishConsoleSeedTask(seedStarted);
        }
    }

    static boolean setExternalTaskCaptionTransport(
            final NubiaCaptionVisibilityManager.Transport transport) {
        if (!ShellAccess.isReady()) {
            return true;
        }
        return NubiaCaptionVisibilityManager.setTransport(transport);
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
