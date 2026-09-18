package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Converts an X11 Exec launch into an ordinary built-in window launch. */
final class X11ApplicationLaunch {
    static boolean reuse(DesktopLaunchContext host, DesktopLaunchRequest request,
            DesktopActivityLaunchResult.Completion completion) {
        if (request.exec == null || request.exec.x11 == null || request.exec.terminal
                || request.sourceShortcut == null || !request.arguments.isEmpty()
                || request.presentation.instancePolicy == DesktopTaskInstancePolicy.CREATE_NEW) return false;
        final var recipe = RecentApplications.describe(host.context(), request.sourceShortcut, request.desktopFilePath);
        final var application = X11Sessions.findRecipe(recipe.key());
        if (application == null) return false;
        final var session = application.session();
        host.hideTransientUi();
        final var destination = host.destination();
        if (!destination.desktop) OrdinaryActivityLaunch.requirePresentation(request.presentation);
        final String uniqueId = host.destinationUniqueId();
        final DesktopActivityLaunchResult.Completion done = result -> {
            if (result.succeeded()) session.recordUse(application.window(), RecentLaunchScope.of(destination));
            else host.onFailure(request, new IllegalStateException(result.error));
            if (completion != null) completion.onComplete(result);
        };
        final int taskId = session.hostTaskId(application.window());
        if (taskId >= 0) {
            TaskCommandQueue.execute(() -> {
                try {
                    destination.requireCurrent(DesktopRuntimeBridge.workspaceDisplayIds());
                    InteractiveActivityLaunch.requireDestination(host.context(), destination.displayId, uniqueId);
                    final var local = destination.desktop ? InteractiveActivityLaunch.OwnTaskResult.NEEDS_PLACEMENT
                            : InteractiveActivityLaunch.showOwnTask(host.context(), taskId, destination.displayId);
                    if (local == InteractiveActivityLaunch.OwnTaskResult.SHOWN) {
                        host.onMain(() -> done.onComplete(DesktopActivityLaunchResult.observedTask(taskId, destination.displayId, true)));
                        return;
                    }
                    if (local == InteractiveActivityLaunch.OwnTaskResult.MISSING) {
                        host.onMain(() -> reopen(host, request, application, done));
                        return;
                    }
                    final var snapshot = TaskRepository.loadAllNow();
                    if (!snapshot.available) throw new java.io.IOException(snapshot.error);
                    final var task = snapshot.tasks.stream().filter(item -> item.taskId == taskId
                            && host.context().getPackageName().equals(item.packageName)
                            && AppProfile.current(host.context()).owns(item.userId))
                            .findFirst().orElseThrow(() -> new java.io.IOException("X11 window has closed; select it again"));
                    ApplicationTaskPlacement.place(task, destination, uniqueId, request.presentation, result ->
                            host.onMain(() -> done.onComplete(result.success
                                    ? DesktopActivityLaunchResult.observedTask(taskId, destination.displayId, true)
                                    : DesktopActivityLaunchResult.failed(result.message))));
                } catch (java.io.IOException | RuntimeException error) {
                    host.onMain(() -> done.onComplete(DesktopActivityLaunchResult.failed(error)));
                }
            });
        } else {
            reopen(host, request, application, done);
        }
        return true;
    }

    private static void reopen(DesktopLaunchContext host, DesktopLaunchRequest request,
            X11Sessions.Application application, DesktopActivityLaunchResult.Completion done) {
        var session = application.session();
        Intent intent = X11Activity.windowIntent(host.context(), session, application.window())
                .putExtra(X11Activity.APPLICATION, session.application);
        AppLaunchTarget target = AppLaunchTarget.explicit(host.context().getPackageName(), X11Activity.class.getName(), "");
        final var reopen = new DesktopLaunchRequest(request.name, request.icon,
                AndroidLaunchSpec.intent(target, intent.toUri(Intent.URI_INTENT_SCHEME)), null, null,
                request.presentation.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW), request.arguments, request.desktopFilePath);
        if (!host.launchAndroid(reopen, null, done)) done.onComplete(DesktopActivityLaunchResult.failed("X11 window is unavailable"));
    }

    static DesktopLaunchRequest prepare(DesktopLaunchContext host, DesktopLaunchRequest request) {
        if (request.exec == null || request.exec.x11 == null) return request;
        if (request.androidLaunch != null || request.androidShortcut != null)
            throw new IllegalArgumentException("X11 commands cannot also launch an Android application");
        Context context = host.context();
        Intent intent = X11Activity.createApplicationIntent(context, request.name,
                request.exec.command, request.exec.workingDirectory).putExtra(X11Activity.DESKTOP_FILE, request.desktopFilePath)
                .putExtra(X11Activity.BACKEND, request.exec.backend.wireName)
                .putExtra(X11Activity.KEYBOARD_DIRECTORY, request.exec.x11.keyboardDirectory())
                .putExtra(X11Activity.APPLICATION, !request.exec.x11.desktop())
                .putExtra(X11Activity.RECENT_SCOPE, RecentLaunchScope.of(host.destination()).name());
        if (request.sourceShortcut != null) {
            var recipe = RecentApplications.describe(context, request.sourceShortcut, request.desktopFilePath);
            intent.putExtra(X11Activity.RECIPE, DesktopEntryFile.encodeRecent(recipe));
            BuiltInWindowIdentity.bind(intent, reference(context, recipe));
        }
        AppLaunchTarget target = AppLaunchTarget.explicit(context.getPackageName(), X11Activity.class.getName(), "");
        return new DesktopLaunchRequest(request.name, request.icon,
                AndroidLaunchSpec.intent(target, intent.toUri(Intent.URI_INTENT_SCHEME)), null, null,
                request.presentation.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW),
                request.arguments, request.desktopFilePath);
    }

    static AppReference reference(Context context, RecentApplicationStore.Entry recipe) {
        return recipe == null ? null : AppReference.hosted(AppProfile.current(context).reference(
                AppLaunchTarget.explicit(context.getPackageName(), X11Activity.class.getName(), "")), recipe.key());
    }

    private X11ApplicationLaunch() { }
}
