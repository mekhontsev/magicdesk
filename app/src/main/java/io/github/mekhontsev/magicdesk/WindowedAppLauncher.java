package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;

import java.io.IOException;

/** Launches or reuses one app task as a native desktop window. */
final class WindowedAppLauncher {
    private static final String LAUNCH_RESULT = "task-display-area-launch=";

    private WindowedAppLauncher() {
    }

    static void launch(
            final Intent launchIntent,
            final String packageName,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds) throws IOException {
        final Rect bounds = FloatingWindowController.getWindowBounds(
                displayId, preferredBounds);

        // Blindaje preventivo: Asegurar que los límites iniciales tengan un ancho y alto válidos
        // antes de construir el comando de lanzamiento para evitar pantallas negras en apps multimedia.
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            bounds.set(100, 100, 900, 700);
        }

        final boolean nativeDesktop = NativeDesktopController.shouldUse();
        final ExistingTaskController.ReuseResult existing = reuse(
                nativeDesktop,
                packageName,
                displayId,
                preservedTaskIds,
                false,
                explicitWindowed,
                bounds);
        if (existing.found) {
            return;
        }

        final ComponentName component = launchIntent.getComponent();
        if (component == null) {
            throw new IOException("launcher activity is not explicit");
        }
        final boolean restoreTouchpad =
                ConsoleModeSwitcher.isTouchpadVisible();
        if (restoreTouchpad) {
            DesktopTaskController.expectTouchpadDisplacement();
        }
        int taskId = -1;
        try {
            final String launchCommand =
                    DesktopRuntimeBridge.isSimulatedDesktopDisplay(displayId)
                            ? TaskDisplayAreaLaunchCommand
                            .createTemporaryAreaAppLaunchCommand(
                                    launchIntent,
                                    displayId,
                                    bounds)
                            : TaskDisplayAreaLaunchCommand
                            .createDefaultAreaAppLaunchCommand(
                                    launchIntent,
                                    displayId,
                                    bounds);
            final String output = ShellAccess.run(
                    launchCommand);
            taskId = parseTaskId(output);
            if (explicitWindowed) {
                DesktopTaskController.beginExplicitWindowedLaunch(taskId);
            }
            final ExistingTaskController.ReuseResult launched = reuse(
                    nativeDesktop,
                    packageName,
                    displayId,
                    preservedTaskIds,
                    true,
                    false,
                    bounds);
            if (!launched.found) {
                throw new IOException("launched task not found");
            }
        } finally {
            if (explicitWindowed && taskId >= 0) {
                DesktopTaskController.finishExplicitWindowedLaunch(taskId);
            }
            if (restoreTouchpad) {
                DesktopTaskController.finishTouchpadPreservation();
                ConsoleModeSwitcher.restoreTouchpadIfMissing();
            }
        }
    }

    private static ExistingTaskController.ReuseResult reuse(
            final boolean nativeDesktop,
            final String packageName,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean waitForTask,
            final boolean explicitWindowed,
            final Rect targetBounds) throws IOException {
        return nativeDesktop
                ? ExistingTaskController.reuseNativeDesktopIfExists(
                packageName,
                displayId,
                preservedTaskIds,
                waitForTask,
                explicitWindowed,
                targetBounds)
                : ExistingTaskController.reuseFreeformIfExists(
                packageName,
                displayId,
                preservedTaskIds,
                waitForTask,
                explicitWindowed,
                targetBounds);
    }

    private static int parseTaskId(final String output) throws IOException {
        final int start = output.indexOf(LAUNCH_RESULT);
        if (start < 0) {
            throw new IOException(output.trim());
        }
        final int valueStart = start + LAUNCH_RESULT.length();
        int valueEnd = valueStart;
        while (valueEnd < output.length()
                && Character.isDigit(output.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            throw new IOException("window launch returned no task id");
        }
        return Integer.parseInt(output.substring(valueStart, valueEnd));
    }
}