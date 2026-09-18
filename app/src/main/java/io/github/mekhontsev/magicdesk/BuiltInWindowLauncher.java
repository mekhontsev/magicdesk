package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.List;

/** Selects ordinary Activity placement or the existing managed desktop path. */
final class BuiltInWindowLauncher {
    interface Callback {
        void onComplete(Throwable error);
    }

    private BuiltInWindowLauncher() {
    }

    static void launch(
            final Activity activity,
            final Intent intent,
            final AppLaunchTarget target,
            final Callback callback) {
        final int displayId = activity.getDisplay() == null
                ? 0 : activity.getDisplay().getDisplayId();
        launch(activity, intent, target, ToolLaunchTarget.resolve("auto", displayId,
                DesktopRuntimeBridge.workspaceDisplayIds()), null, callback);
    }

    static void launch(final Context context, final Intent source,
            final AppLaunchTarget target, final ToolLaunchTarget placement,
            final String uniqueId, final Callback callback) {
        launch(context, source, target, placement, uniqueId, null, callback);
    }

    static void launch(final Context context, final Intent source,
            final AppLaunchTarget target, final ToolLaunchTarget placement,
            final String uniqueId, final DesktopLaunchPresentation presentation, final Callback callback) {
        final Intent intent;
        try {
            intent = presentation == null ? new Intent(source)
                    : presentation.instancePolicy.applyTo(context.getPackageManager(), source);
        } catch (RuntimeException error) {
            complete(context, callback, error);
            return;
        }
        final int displayId = placement.displayId;
        final Callback launched = error -> {
            if (error == null) RecentApplications.recordBuiltIn(context, intent, target, RecentLaunchScope.of(placement));
            complete(context, callback, error);
        };
        TaskCommandQueue.execute(() -> {
            try {
                // Check again on the command queue: a session or display may have
                // changed between a UI selection and execution.
                placement.requireCurrent(DesktopRuntimeBridge.workspaceDisplayIds());
                InteractiveActivityLaunch.requireDestination(context, displayId, uniqueId);
                if (!placement.desktop) {
                    if (presentation != null) { OrdinaryActivityLaunch.requirePresentation(presentation); }
                    final BuiltInDesktopAppCatalog.Entry entry =
                            BuiltInDesktopAppCatalog.find(target);
                    if (entry == null) { throw new IllegalArgumentException("unknown built-in application"); }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (presentation == null && entry.multipleWindows) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    } else if (presentation == null && BuiltInWindowRegistry.needsSeparateTask(target, displayId)) {
                        // An ordinary tool launch must not steal an instance from
                        // another display, especially one owned by Desktop.
                        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    }
                    if (InteractiveActivityLaunch.canLaunchLocally(context, displayId)) {
                        // Android checks the actual Intent and display under the app UID.
                        // A rejected public launch never retries with shell authority.
                        new Handler(Looper.getMainLooper()).post(() -> {
                            final Activity activity = (Activity) context;
                            if (activity.isFinishing() || activity.isDestroyed()) { return; }
                            try {
                                InteractiveActivityLaunch.requireDestination(context, displayId, uniqueId);
                                InteractiveActivityLaunch.launch(context, intent,
                                        AndroidLaunchSpec.Delivery.SHELL_INTENT, displayId);
                                launched.onComplete(null);
                            } catch (IOException | RuntimeException error) {
                                launched.onComplete(error);
                            }
                        });
                    } else {
                        OrdinaryActivityLaunch.launch(context, intent,
                                AndroidLaunchSpec.Delivery.SHELL_INTENT, displayId);
                        launched.onComplete(null);
                    }
                    return;
                }
                if (presentation != null && presentation.mode == DesktopLaunchMode.FULLSCREEN) {
                    final DesktopLaunchRequest request = new DesktopLaunchRequest(target.packageName, "",
                            AndroidLaunchSpec.intent(target, intent.toUri(Intent.URI_INTENT_SCHEME)),
                            null, null, presentation, DesktopLaunchArguments.empty(), "");
                    DesktopRuntimeBridge.launchAutomationRequest(request, displayId,
                            result -> launched.onComplete(
                                    result.hasObservedTask() ? null : new IOException(result.error)));
                    return;
                }
                List<TaskRepository.TaskEntry> visibleTasks =
                        MagicDeskRuntime.getVisibleFreeformTasks(displayId);
                if (visibleTasks == null || visibleTasks.isEmpty()) {
                    visibleTasks = DesktopTaskController
                            .selectVisibleFreeformTasks(
                                    TaskRepository.loadNow(displayId));
                }
                final WindowedAppLauncher.LaunchResult launch =
                        presentation == null ? WindowedAppLauncher.launchBuiltInWindow(
                                intent,
                                target,
                                displayId,
                                taskIds(visibleTasks),
                                () -> DesktopRuntimeBridge.syncTaskbarWithSnapshot(
                                        displayId,
                                        TaskRepository.loadNow(displayId)))
                                : WindowedAppLauncher.launch(intent, target, displayId,
                                        taskIds(visibleTasks), true,
                                        presentation.bounds == null ? BuiltInDesktopAppCatalog.defaultWindowBounds(target)
                                                : presentation.bounds,
                                        presentation.instancePolicy,
                                        () -> DesktopRuntimeBridge.syncTaskbarWithSnapshot(displayId,
                                                TaskRepository.loadNow(displayId)));
                launch.whenReady(result -> launched.onComplete(
                        result.success ? null : new IOException(result.message)));
            } catch (IOException | RuntimeException error) {
                launched.onComplete(error);
            }
        });
    }

    private static int[] taskIds(
            final List<TaskRepository.TaskEntry> tasks) {
        final int[] ids = new int[tasks == null ? 0 : tasks.size()];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = tasks.get(index).taskId;
        }
        return ids;
    }

    private static void complete(
            final Context context,
            final Callback callback,
            final Throwable error) {
        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!(context instanceof Activity activity)
                        || !activity.isFinishing() && !activity.isDestroyed()) {
                    callback.onComplete(error);
                }
            });
        }
    }
}
