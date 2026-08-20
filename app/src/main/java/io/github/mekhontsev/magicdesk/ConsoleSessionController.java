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

    static void show(
            final int displayId,
            final PlatformProjectionDriver projection) {
        if (projection == null) {
            throw new IllegalArgumentException("projection driver is required");
        }
        final android.content.Context context =
                MagicDeskApplication.applicationContext();
        int consoleDisplayId = displayId > 0
                ? displayId : projection.activeDesktopDisplayId(context);
        final boolean adoptedConsoleMode =
                consoleDisplayId > android.view.Display.DEFAULT_DISPLAY;
        boolean seedStarted = false;
        int physicalDisplayId = -1;
        PlatformProjectionDriver.PreparedMode preparedMode = null;
        DisplayProfileStore.Profile displayProfile = null;
        try {
            // A published Console display was already configured by the
            // firmware. Only a missing display may enter output preparation
            // and request a new expand transition.
            if (consoleDisplayId <= 0) {
                physicalDisplayId =
                        ConsoleDisplayController.findExternalDisplayId();
                if (physicalDisplayId <= 0) {
                    throw new IOException(
                            "no physical external display was reported");
                }
                displayProfile = DisplayProfileController
                        .prepareExternalProfile(
                                context,
                                physicalDisplayId);
                if (projection.isMirrorMode()
                        && !hasVisibleAppTask(0)) {
                    seedStarted = startConsoleSeedTask();
                    if (!seedStarted) {
                        return;
                    }
                }
                try {
                    preparedMode = projection.prepareExternalDisplay(
                            context,
                            physicalDisplayId,
                            displayProfile);
                    physicalDisplayId = preparedMode.physicalDisplayId();
                } catch (IOException | RuntimeException error) {
                    Log.w(TAG, "Cannot prepare external display mode", error);
                    CompatibilityDiagnostics.record(
                            "DISPLAY-MODE-001",
                            "Could not apply the external display launch settings",
                            error.getMessage(),
                            error);
                    return;
                }
                if (!projection.requestDesktopMode(
                        physicalDisplayId)) {
                    return;
                }
                consoleDisplayId = projection.waitForDesktopDisplay(context);
                if (consoleDisplayId <= 0) {
                    throw new IOException(
                            "projection platform did not create a desktop display");
                }
                try {
                    if (preparedMode.applyDeferredMode()) {
                        physicalDisplayId = preparedMode.physicalDisplayId();
                        consoleDisplayId =
                                projection.waitForDesktopDisplay(context);
                        if (consoleDisplayId <= 0) {
                            throw new IOException(
                                    "Console display disappeared after changing"
                                            + " the native output mode");
                        }
                    }
                } catch (IOException | RuntimeException error) {
                    Log.w(TAG, "Cannot restore the exact native output mode", error);
                    CompatibilityDiagnostics.record(
                            "DISPLAY-MODE-001",
                            "Could not apply the external display launch settings",
                            error.getMessage(),
                            error);
                    final int currentDisplayId =
                            ConsoleDisplayController.findExternalDisplayId();
                    if (currentDisplayId > 0) {
                        physicalDisplayId = currentDisplayId;
                    }
                }
            }
            if (adoptedConsoleMode) {
                Log.i(TAG, "adopting existing Console display="
                        + consoleDisplayId
                        + " without changing the output mode");
            }
            ConsoleDisplayController.ensureLandscape(consoleDisplayId);
            if (displayProfile == null) {
                physicalDisplayId =
                        ConsoleDisplayController.findExternalDisplayId();
                displayProfile = DisplayProfileController
                        .prepareExternalProfile(
                                context,
                                physicalDisplayId);
            }
            DesktopDisplayTarget target =
                    DesktopDisplayTarget.wired(consoleDisplayId)
                            .withActivationSource(adoptedConsoleMode
                                    ? DesktopDisplayTarget.ActivationSource
                                            .ADOPTED_EXISTING
                                    : DesktopDisplayTarget.ActivationSource
                                            .MAGICDESK_REQUESTED);
            if (displayProfile != null && physicalDisplayId > 0) {
                target = target.withProfile(
                        physicalDisplayId, displayProfile.key);
            }
            DesktopDisplayDriverSupport.showReadySecondary(target);
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
                "DISPLAY-SESSION-004",
                "The external desktop needs a foreground application",
                output);
        return false;
    }

    private static void finishConsoleSeedTask(final boolean seedStarted) {
        if (seedStarted) {
            ConsoleSeedActivity.finishActive();
        }
    }

}
