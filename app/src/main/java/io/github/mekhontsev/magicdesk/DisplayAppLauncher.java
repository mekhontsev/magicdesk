package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.view.Display;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Launch destination follows current workspace ownership, not the role of the Start host. */
final class DisplayAppLauncher {
    private DisplayAppLauncher() { }

    static void launch(Activity activity, AppItem app, int displayId, String uniqueId,
            BooleanSupplier canLaunch, Runnable onStarted, Consumer<Throwable> onFailure) {
        launch(activity, app, displayId, uniqueId, DesktopLaunchPresentation.automatic(),
                canLaunch, onStarted, onFailure);
    }

    static void launch(Activity activity, AppItem app, int displayId, String uniqueId,
            DesktopLaunchPresentation presentation, BooleanSupplier canLaunch,
            Runnable onStarted, Consumer<Throwable> onFailure) {
        TaskCommandQueue.execute(() -> {
            try {
                app.identity.requireProfile(AppProfile.current(activity));
                DesktopDisplayCatalog.require(displayId, uniqueId);
                if (!canLaunch.getAsBoolean()) { return; }
                if (DesktopDisplayDrivers.hasActiveWorkspace(displayId)) {
                    // The gateway marshals to UI and awaits admission. Its caller
                    // must stay on the command queue, not block that same UI thread.
                    final DesktopLaunchRequest integrated = presentation.mode == DesktopLaunchMode.AUTO
                            ? DesktopLaunchIntegrationRegistry.defaultRequest(activity, app) : null;
                    final boolean accepted = integrated != null
                            ? DesktopRuntimeBridge.launchAutomationRequest(integrated, displayId)
                            : DesktopRuntimeBridge.launchApplication(app.identity, app.launchTarget,
                                    presentation, displayId);
                    activity.runOnUiThread(() -> {
                        if (!canLaunch.getAsBoolean()) { return; }
                        if (accepted) { onStarted.run(); }
                        else { onFailure.accept(new IllegalStateException("desktop launch was rejected")); }
                    });
                    return;
                }
                OrdinaryActivityLaunch.requirePresentation(presentation);
                final Intent intent = app.launchTarget.resolve(activity.getPackageManager());
                if (intent == null) { throw new IllegalStateException("launcher activity is unavailable"); }
                if (BuildConfig.APPLICATION_ID.equals(app.packageName)
                        && BuiltInDesktopAppCatalog.findComponent(app.launchTarget.activityClassName) != null) {
                    activity.runOnUiThread(() -> {
                        if (!canLaunch.getAsBoolean()) { return; }
                        ToolApplications.open(activity, intent,
                                ToolLaunchTarget.resolve("auto", displayId,
                                        DesktopRuntimeBridge.workspaceDisplayIds()), uniqueId,
                                error -> {
                                    if (error == null) { onStarted.run(); }
                                    else { onFailure.accept(error); }
                                });
                    });
                    return;
                }
                final LaunchActivityIdentity identity = LaunchActivityIdentity.resolve(
                        app.profile.userId, activity.getPackageManager(), app.launchTarget);
                final TaskRepository.Snapshot snapshot = TaskRepository.loadAllNow();
                if (!snapshot.available) { throw new IllegalStateException(snapshot.error); }
                final Runnable start = () -> {
                    if (!canLaunch.getAsBoolean()) { return; }
                    try {
                        if (DesktopDisplayDrivers.hasActiveWorkspace(displayId)) {
                            throw new IllegalStateException("display ownership changed during launch");
                        }
                        DesktopDisplayCatalog.require(displayId, uniqueId);
                        if (presentation.instancePolicy == DesktopTaskInstancePolicy.CREATE_NEW) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        // A visible ordinary phone host can use public Activity APIs.
                        // Other destinations retain the shared privileged placement path.
                        if (displayId == Display.DEFAULT_DISPLAY
                                && activity.getDisplay() != null
                                && activity.getDisplay().getDisplayId() == displayId
                                && !BuildConfig.APPLICATION_ID.equals(app.packageName)) {
                            activity.runOnUiThread(() -> {
                                if (!canLaunch.getAsBoolean()) { return; }
                                try {
                                    final ActivityOptions options = ActivityOptions.makeBasic();
                                    options.setLaunchDisplayId(displayId);
                                    activity.startActivity(intent, options.toBundle());
                                    onStarted.run();
                                } catch (RuntimeException error) { onFailure.accept(error); }
                            });
                        } else {
                            OrdinaryActivityLaunch.launch(activity, intent,
                                    AndroidLaunchSpec.Delivery.SHELL_INTENT, displayId);
                            activity.runOnUiThread(onStarted);
                        }
                    } catch (java.io.IOException | RuntimeException error) {
                        activity.runOnUiThread(() -> onFailure.accept(error));
                    }
                };
                final TaskRepository.TaskEntry transfer = presentation.instancePolicy == DesktopTaskInstancePolicy.REUSE_EXISTING
                        ? selectTransfer(identity, snapshot, displayId) : null;
                if (transfer == null) { start.run(); }
                else {
                    TaskRepository.moveTaskToDisplay(transfer, displayId, uniqueId, null, result -> {
                        if (result.success) { TaskCommandQueue.execute(start); }
                        else { activity.runOnUiThread(() -> onFailure.accept(
                                new IllegalStateException(result.message))); }
                    });
                }
            } catch (java.io.IOException | RuntimeException error) {
                activity.runOnUiThread(() -> onFailure.accept(error));
            }
        });
    }

    static TaskRepository.TaskEntry selectTransfer(LaunchActivityIdentity identity,
            TaskRepository.Snapshot snapshot, int displayId) {
        final java.util.List<TaskRepository.TaskEntry> tasks = new java.util.ArrayList<>(snapshot.tasks);
        tasks.addAll(snapshot.phoneTasks);
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task.displayId == displayId && !task.home && identity.matchesTask(task)) { return null; }
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task.displayId != displayId && DesktopManagedTaskPolicy.isManagedApplicationTask(task)
                    && identity.matchesTask(task)) { return task; }
        }
        return null;
    }
}
