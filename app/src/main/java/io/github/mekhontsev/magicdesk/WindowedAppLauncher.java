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
            final boolean explicitWindowed) throws IOException {
        final boolean nativeDesktop = NativeDesktopController.shouldUse();
        final ExistingTaskController.ReuseResult existing = reuse(
                nativeDesktop,
                packageName,
                displayId,
                preservedTaskIds,
                false,
                explicitWindowed);
        if (existing.found) {
            return;
        }

        final ComponentName component = launchIntent.getComponent();
        if (component == null) {
            throw new IOException("launcher activity is not explicit");
        }
        final Rect bounds =
                FloatingWindowController.getDefaultWindowBounds(displayId);
        final boolean restoreTouchpad =
                ConsoleModeSwitcher.isTouchpadVisible();
        if (restoreTouchpad) {
            DesktopTaskController.expectTouchpadDisplacement();
        }
        int taskId = -1;
        try {
            final String output = ShellAccess.run(
                    TaskDisplayAreaLaunchCommand.createAppLaunchCommand(
                            launchIntent,
                            displayId,
                            bounds));
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
                    false);
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
            final boolean explicitWindowed) throws IOException {
        return nativeDesktop
                ? ExistingTaskController.reuseNativeDesktopIfExists(
                        packageName,
                        displayId,
                        preservedTaskIds,
                        waitForTask,
                        explicitWindowed)
                : ExistingTaskController.reuseFreeformIfExists(
                        packageName,
                        displayId,
                        preservedTaskIds,
                        waitForTask,
                        explicitWindowed);
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
