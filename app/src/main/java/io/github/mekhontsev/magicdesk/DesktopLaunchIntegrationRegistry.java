package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.List;

/** Small registry for optional app integrations; it owns no launch state. */
final class DesktopLaunchIntegrationRegistry {
    private static final List<DesktopLaunchIntegration> INTEGRATIONS =
            List.of(new TermuxX11LaunchIntegration());

    private DesktopLaunchIntegrationRegistry() {
    }

    static DesktopLaunchRequest defaultRequest(
            final Context context, final AppItem app) {
        if (app == null) {
            return null;
        }
        final DesktopLaunchIntegration integration = find(
                app.launchTarget);
        if (integration == null || !integration.isAvailable(context)) {
            return null;
        }
        return new DesktopLaunchRequest(
                app.label,
                app.packageName,
                AndroidLaunchSpec.defaultLaunch(app.launchTarget),
                integration.defaultExec(context),
                DesktopLaunchMode.AUTO);
    }

    static DesktopLaunchRequest prepare(
            final Context context, final DesktopLaunchRequest request) {
        if (request == null
                || request.androidLaunch == null
                || request.exec == null) {
            return request;
        }
        final DesktopLaunchIntegration integration = find(
                request.androidLaunch.target);
        if (integration == null) {
            return request;
        }
        return request.withExec(
                integration.prepareExec(context, request.exec));
    }

    static DesktopApplicationShortcut defaultShortcut(
            final Context context,
            final AppItem app,
            final String name) {
        final DesktopLaunchIntegration integration = app == null
                ? null : find(app.launchTarget);
        if (integration == null || !integration.isAvailable(context)) {
            return null;
        }
        final DesktopExecSpec exec = integration.defaultExec(context);
        return new DesktopApplicationShortcut(
                name,
                app.packageName,
                exec.command,
                app.launchTarget,
                "",
                DesktopLaunchMode.AUTO,
                false,
                exec.backend,
                exec.terminal);
    }

    static boolean isDefaultShortcut(
            final Context context,
            final AppItem app,
            final DesktopApplicationShortcut shortcut) {
        if (app == null || shortcut == null || shortcut.launchTarget == null) {
            return false;
        }
        final DesktopLaunchIntegration integration = find(
                app.launchTarget);
        return integration != null
                && integration.isAvailable(context)
                && integration.matches(shortcut.launchTarget)
                && shortcut.hasExecLaunch()
                && shortcut.execBackend
                        == integration.defaultExec(context).backend;
    }

    private static DesktopLaunchIntegration find(
            final AppLaunchTarget target) {
        for (final DesktopLaunchIntegration integration : INTEGRATIONS) {
            if (integration.matches(target)) {
                return integration;
            }
        }
        return null;
    }
}
