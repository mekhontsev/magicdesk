package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Converts an X11 Exec launch into an ordinary built-in window launch. */
final class X11ApplicationLaunch {
    static DesktopLaunchRequest prepare(DesktopLaunchContext host, DesktopLaunchRequest request) {
        if (request.exec == null || request.exec.backend != DesktopExecBackend.X11) return request;
        if (request.androidLaunch != null || request.androidShortcut != null)
            throw new IllegalArgumentException("X11 commands cannot also launch an Android application");
        if (request.exec.terminal) return request.withExec(new DesktopExecSpec(DesktopExecBackend.TERMUX,
                request.exec.command, true, request.exec.workingDirectory));
        Context context = host.activity();
        Intent intent = X11Activity.createApplicationIntent(context, request.name,
                request.exec.command, request.exec.workingDirectory).putExtra(X11Activity.DESKTOP_FILE, request.desktopFilePath);
        AppLaunchTarget target = AppLaunchTarget.explicit(context.getPackageName(), X11Activity.class.getName(), "");
        return new DesktopLaunchRequest(request.name, request.icon,
                AndroidLaunchSpec.intent(target, intent.toUri(Intent.URI_INTENT_SCHEME)), null, null,
                request.presentation.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW),
                request.arguments, request.desktopFilePath);
    }

    private X11ApplicationLaunch() { }
}
