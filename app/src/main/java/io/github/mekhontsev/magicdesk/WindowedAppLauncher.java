package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;

import java.io.IOException;

/** Launches or reuses one app task as a native desktop window. */
final class WindowedAppLauncher {
    private static final int WINDOWING_MODE_FREEFORM = 5;

    enum TaskReusePolicy {
        REUSE_EXISTING,
        CREATE_NEW
    }

    interface TaskReadyCallback {
        void onTaskReady();
    }

    private interface FreshTaskLauncher {
        int launch(int displayId, Rect bounds) throws IOException;
    }

    interface ExistingTaskLauncher {
        void launch(int displayId, int taskId, Rect bounds)
                throws IOException;
    }

    static final class LaunchResult {
        final int taskId;
        final boolean reused;

        LaunchResult(final int taskId, final boolean reused) {
            this.taskId = taskId;
            this.reused = reused;
        }
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

    static LaunchResult launch(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final TaskReusePolicy reusePolicy,
            final TaskReadyCallback taskReadyCallback) throws IOException {
        return launch(
                launchIntent,
                launchTarget,
                displayId,
                preservedTaskIds,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                null,
                taskReadyCallback);
    }

    static LaunchResult launch(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final TaskReusePolicy reusePolicy,
            final ExistingTaskLauncher existingTaskLauncher,
            final TaskReadyCallback taskReadyCallback) throws IOException {
        final boolean createNew = reusePolicy == TaskReusePolicy.CREATE_NEW;
        if (createNew) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        final ComponentName component = launchIntent.getComponent();
        if (component == null) {
            throw new IOException("launcher activity is not explicit");
        }
        final String launchKind = BuiltInDesktopAppCatalog.find(launchTarget)
                == null ? "desktop-window" : "built-in-window";
        return launch(
                launchTarget,
                displayId,
                preservedTaskIds,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                taskReadyCallback,
                launchKind,
                (targetDisplayId, bounds) ->
                        MagicDeskRuntime.launchWindowedTask(
                                targetDisplayId, launchIntent, bounds),
                existingTaskLauncher);
    }

    static LaunchResult launchShortcut(
            final AppShortcutAction shortcut,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final TaskReadyCallback taskReadyCallback) throws IOException {
        if (shortcut == null) {
            throw new IOException("app shortcut is required");
        }
        return launch(
                shortcut.taskTarget(),
                displayId,
                preservedTaskIds,
                explicitWindowed,
                preferredBounds,
                TaskReusePolicy.REUSE_EXISTING,
                taskReadyCallback,
                "app-shortcut",
                (targetDisplayId, bounds) ->
                        MagicDeskRuntime.launchAppShortcut(
                                targetDisplayId,
                                shortcut.packageName,
                                shortcut.id,
                                shortcut.user,
                                WINDOWING_MODE_FREEFORM,
                                bounds,
                                -1),
                (targetDisplayId, taskId, bounds) ->
                        MagicDeskRuntime.launchAppShortcut(
                                targetDisplayId,
                                shortcut.packageName,
                                shortcut.id,
                                shortcut.user,
                                WINDOWING_MODE_FREEFORM,
                                bounds,
                                taskId));
    }

    static LaunchResult launchPendingActivity(
            final PendingIntent pendingIntent,
            final AppLaunchTarget launchTarget,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final TaskReusePolicy reusePolicy,
            final TaskReadyCallback taskReadyCallback) throws IOException {
        if (pendingIntent == null || launchTarget == null) {
            throw new IOException(
                    "pending Activity launch target is required");
        }
        return launch(
                launchTarget,
                displayId,
                preservedTaskIds,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                taskReadyCallback,
                "android-pending-activity",
                (targetDisplayId, bounds) ->
                        MagicDeskRuntime.launchPendingActivity(
                                targetDisplayId,
                                launchTarget,
                                pendingIntent,
                                WINDOWING_MODE_FREEFORM,
                                bounds,
                                -1),
                (targetDisplayId, taskId, bounds) ->
                        MagicDeskRuntime.launchPendingActivity(
                                targetDisplayId,
                                launchTarget,
                                pendingIntent,
                                WINDOWING_MODE_FREEFORM,
                                bounds,
                                taskId));
    }

    private static LaunchResult launch(
            final AppLaunchTarget launchTarget,
            final int displayId,
            final int[] preservedTaskIds,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final TaskReusePolicy reusePolicy,
            final TaskReadyCallback taskReadyCallback,
            final String launchKind,
            final FreshTaskLauncher freshTaskLauncher,
            final ExistingTaskLauncher existingTaskLauncher)
            throws IOException {
        final Rect bounds = FloatingWindowController.getWindowBounds(
                displayId, preferredBounds);
        final boolean nativeDesktop =
                !DesktopDisplayDrivers.hasActiveWorkspace(displayId)
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
                if (existingTaskLauncher != null) {
                    existingTaskLauncher.launch(
                            displayId, existing.taskId, bounds);
                }
                return completeReusedTask(
                        displayId,
                        existing.taskId,
                        existing.originalDisplayId,
                        launchPath(launchKind, createNew, true));
            }
        }
        try (WindowedTaskLaunchLease launchLease =
                WindowedTaskLaunchLease.acquire()) {
            final int taskId = freshTaskLauncher.launch(displayId, bounds);
            if (taskReadyCallback != null) {
                taskReadyCallback.onTaskReady();
            }
            if (explicitWindowed) {
                launchLease.protectStartupTask(taskId);
            }
            return completeLaunch(
                    displayId,
                    taskId,
                    displayId,
                    launchPath(launchKind, createNew, false));
        }
    }

    private static LaunchResult completeLaunch(
            final int displayId,
            final int taskId,
            final int originalDisplayId,
            final String launchPath) {
        DesktopTaskLaunchDiagnostics.note(
                taskId, originalDisplayId, displayId, launchPath);
        MagicDeskRuntime.noteTaskLaunchFocus(displayId, taskId);
        return new LaunchResult(taskId, false);
    }

    private static LaunchResult completeReusedTask(
            final int displayId,
            final int taskId,
            final int originalDisplayId,
            final String launchPath) {
        MagicDeskRuntime.focusDesktopTask(displayId, taskId, null);
        DesktopTaskLaunchDiagnostics.note(
                taskId, originalDisplayId, displayId, launchPath);
        MagicDeskRuntime.noteTaskLaunchFocus(displayId, taskId);
        return new LaunchResult(taskId, true);
    }

    private static String launchPath(
            final String kind,
            final boolean createNew,
            final boolean reused) {
        return kind + (createNew ? "-new-document"
                : reused ? "-reuse" : "-new");
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
