package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;

import java.io.IOException;

/** Launches or reuses one app task as a native desktop window. */
final class WindowedAppLauncher {
    private static final String LAUNCH_RESULT = "task-display-area-launch=";

    enum TaskReusePolicy {
        REUSE_EXISTING,
        CREATE_NEW
    }

    interface TaskReadyCallback {
        void onTaskReady();
    }

    private WindowedAppLauncher() {
    }

    static void launchBuiltInWindow(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final int displayId,
            final int[] preservedTaskIds,
            final TaskReadyCallback taskReadyCallback) throws IOException {
        launch(
                launchIntent,
                launchTarget,
                displayId,
                preservedTaskIds,
                true,
                BuiltInDesktopAppCatalog.defaultWindowBounds(launchTarget),
                BuiltInDesktopAppCatalog.supportsMultipleWindows(launchTarget)
                        ? TaskReusePolicy.CREATE_NEW
                        : TaskReusePolicy.REUSE_EXISTING,
                taskReadyCallback);
    }

    static void launch(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final TaskReusePolicy reusePolicy,
            final TaskReadyCallback taskReadyCallback) throws IOException {
        final Rect bounds = FloatingWindowController.getWindowBounds(
                displayId, preferredBounds);
        final boolean nativeDesktop = NativeDesktopController.shouldUse();
        final boolean createNew = reusePolicy == TaskReusePolicy.CREATE_NEW;
        if (!createNew) {
            final ExistingTaskController.ReuseResult existing = reuse(
                    nativeDesktop,
                    launchTarget,
                    displayId,
                    preservedTaskIds,
                    false,
                    explicitWindowed,
                    bounds);
            if (existing.found) {
                return;
            }
        } else {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
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
                    DesktopDisplayDrivers.forActiveDisplay(displayId)
                            .features().temporaryLaunchArea
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
            if (taskReadyCallback != null) {
                taskReadyCallback.onTaskReady();
            }
            if (explicitWindowed) {
                DesktopTaskController.beginExplicitWindowedLaunch(taskId);
            }
            if (createNew) {
                ExistingTaskController.confirmLaunchedWindow(
                        taskId, displayId, preservedTaskIds);
            } else {
                final ExistingTaskController.ReuseResult launched = reuse(
                        nativeDesktop,
                        launchTarget,
                        displayId,
                        preservedTaskIds,
                        true,
                        false,
                        bounds);
                if (!launched.found) {
                    throw new IOException("launched task not found");
                }
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
            final AppLaunchTarget launchTarget,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean waitForTask,
            final boolean explicitWindowed,
            final Rect targetBounds) throws IOException {
        return nativeDesktop
                ? ExistingTaskController.reuseNativeDesktopIfExists(
                        launchTarget,
                        displayId,
                        preservedTaskIds,
                        waitForTask,
                        explicitWindowed,
                        targetBounds)
                : ExistingTaskController.reuseFreeformIfExists(
                        launchTarget,
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
