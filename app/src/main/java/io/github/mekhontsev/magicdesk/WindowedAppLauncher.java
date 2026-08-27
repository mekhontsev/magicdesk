package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;

import java.io.IOException;

/** Launches or reuses one app task as a native desktop window. */
final class WindowedAppLauncher {
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

    static int launch(
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
        final DesktopTaskAreaPolicy taskAreaPolicy =
                DesktopDisplayDrivers.activeTaskAreaPolicy(displayId);
        final boolean nativeDesktop =
                taskAreaPolicy != DesktopTaskAreaPolicy.SESSION
                        && NativeDesktopController.shouldUse();
        final boolean createNew = reusePolicy == TaskReusePolicy.CREATE_NEW;
        if (!createNew) {
            final ExistingTaskController.ReuseResult existing = reuse(
                    nativeDesktop,
                    launchTarget,
                    displayId,
                    preservedTaskIds,
                    false,
                    explicitWindowed,
                    bounds,
                    null);
            if (existing.found) {
                return completeLaunch(displayId, existing.taskId);
            }
        } else if (createNew) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }

        final ComponentName component = launchIntent.getComponent();
        if (component == null) {
            throw new IOException("launcher activity is not explicit");
        }
        try (WindowedTaskLaunchLease launchLease =
                WindowedTaskLaunchLease.acquire()) {
            final int taskId = MagicDeskRuntime.launchWindowedTask(
                    displayId, launchIntent, bounds);
            if (taskReadyCallback != null) {
                taskReadyCallback.onTaskReady();
            }
            if (explicitWindowed) {
                launchLease.protectStartupTask(taskId);
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
                        bounds,
                        launchLease);
                if (!launched.found) {
                    throw new IOException("launched task not found");
                }
                return completeLaunch(displayId, launched.taskId);
            }
            return completeLaunch(displayId, taskId);
        }
    }

    private static int completeLaunch(
            final int displayId, final int taskId) {
        MagicDeskRuntime.noteTaskLaunchFocus(displayId, taskId);
        return taskId;
    }

    private static ExistingTaskController.ReuseResult reuse(
            final boolean nativeDesktop,
            final AppLaunchTarget launchTarget,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean waitForTask,
            final boolean explicitWindowed,
            final Rect targetBounds,
            final WindowedTaskLaunchLease launchLease) throws IOException {
        return nativeDesktop
                ? ExistingTaskController.reuseNativeDesktopIfExists(
                        launchTarget,
                        displayId,
                        preservedTaskIds,
                        waitForTask,
                        explicitWindowed,
                        targetBounds,
                        launchLease)
                : ExistingTaskController.reuseFreeformIfExists(
                        launchTarget,
                        displayId,
                        preservedTaskIds,
                        waitForTask,
                        explicitWindowed,
                        targetBounds,
                        launchLease);
    }

}
