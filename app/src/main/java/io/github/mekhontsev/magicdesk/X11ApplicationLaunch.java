package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Converts an X11 Exec launch into an ordinary built-in window launch. */
final class X11ApplicationLaunch {
    static boolean reuse(DesktopLaunchContext host, DesktopLaunchRequest request,
            DesktopActivityLaunchResult.Completion completion) {
        if (request.exec == null || request.exec.backend != DesktopExecBackend.X11 || request.exec.terminal
                || request.sourceShortcut == null || !request.arguments.isEmpty()
                || request.presentation.instancePolicy == DesktopTaskInstancePolicy.CREATE_NEW) return false;
        final var recipe = RecentApplications.describe(host.activity(), request.sourceShortcut, request.desktopFilePath);
        final var session = X11Sessions.findRecipe(recipe.key());
        if (session == null) return false;
        host.hideTransientUi();
        final var destination = host.destination();
        final String uniqueId = host.destinationUniqueId();
        final DesktopActivityLaunchResult.Completion done = result -> {
            if (result.succeeded()) RecentApplications.record(host.activity(), recipe);
            else host.onFailure(request, new IllegalStateException(result.error));
            if (completion != null) completion.onComplete(result);
        };
        final int taskId = session.hostTaskId();
        if (taskId >= 0) {
            TaskCommandQueue.execute(() -> {
                try {
                    final var snapshot = TaskRepository.loadAllNow();
                    if (!snapshot.available) throw new java.io.IOException(snapshot.error);
                    final var task = snapshot.tasks.stream().filter(item -> item.taskId == taskId
                            && host.activity().getPackageName().equals(item.packageName)
                            && AppProfile.current(host.activity()).owns(item.userId))
                            .findFirst().orElseThrow(() -> new java.io.IOException("X11 window has closed; select it again"));
                    ApplicationTaskPlacement.place(task, destination, uniqueId, request.presentation, result ->
                            host.activity().runOnUiThread(() -> done.onComplete(result.success
                                    ? DesktopActivityLaunchResult.observedTask(taskId, destination.displayId, true)
                                    : DesktopActivityLaunchResult.failed(result.message))));
                } catch (java.io.IOException | RuntimeException error) {
                    host.activity().runOnUiThread(() -> done.onComplete(DesktopActivityLaunchResult.failed(error)));
                }
            });
        } else {
            Intent intent = X11Activity.createIntent(host.activity()).putExtra(X11Activity.SESSION, session.id())
                    .putExtra(X11Activity.APPLICATION, session.application);
            AppLaunchTarget target = AppLaunchTarget.explicit(host.activity().getPackageName(), X11Activity.class.getName(), "");
            final var reopen = new DesktopLaunchRequest(request.name, request.icon,
                    AndroidLaunchSpec.intent(target, intent.toUri(Intent.URI_INTENT_SCHEME)), null, null,
                    request.presentation.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW), request.arguments, request.desktopFilePath);
            if (!host.launchAndroid(reopen, null, done)) done.onComplete(DesktopActivityLaunchResult.failed("X11 window is unavailable"));
        }
        return true;
    }

    static DesktopLaunchRequest prepare(DesktopLaunchContext host, DesktopLaunchRequest request) {
        if (request.exec == null || request.exec.backend != DesktopExecBackend.X11) return request;
        if (request.androidLaunch != null || request.androidShortcut != null)
            throw new IllegalArgumentException("X11 commands cannot also launch an Android application");
        if (request.exec.terminal) return request.withExec(new DesktopExecSpec(DesktopExecBackend.TERMUX,
                request.exec.command, true, request.exec.workingDirectory));
        Context context = host.activity();
        Intent intent = X11Activity.createApplicationIntent(context, request.name,
                request.exec.command, request.exec.workingDirectory).putExtra(X11Activity.DESKTOP_FILE, request.desktopFilePath);
        if (request.sourceShortcut != null) intent.putExtra(X11Activity.RECIPE,
                DesktopEntryFile.encodeRecent(RecentApplications.describe(context, request.sourceShortcut, request.desktopFilePath)));
        AppLaunchTarget target = AppLaunchTarget.explicit(context.getPackageName(), X11Activity.class.getName(), "");
        return new DesktopLaunchRequest(request.name, request.icon,
                AndroidLaunchSpec.intent(target, intent.toUri(Intent.URI_INTENT_SCHEME)), null, null,
                request.presentation.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW),
                request.arguments, request.desktopFilePath);
    }

    private X11ApplicationLaunch() { }
}
