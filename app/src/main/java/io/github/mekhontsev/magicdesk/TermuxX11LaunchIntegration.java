package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Declarative Termux command paired with the Termux:X11 Android viewer. */
final class TermuxX11LaunchIntegration
        implements DesktopLaunchIntegration {
    @Override
    public boolean matches(final AppLaunchTarget target) {
        return target != null
                && TermuxX11Integration.PACKAGE_NAME.equals(
                        target.packageName);
    }

    @Override
    public boolean isAvailable(final Context context) {
        return TermuxX11Integration.isAvailable(context);
    }

    @Override
    public DesktopExecSpec defaultExec(final Context context) {
        return new DesktopExecSpec(
                DesktopExecBackend.TERMUX,
                MagicDeskSettings.load().termuxX11StartupCommand,
                false);
    }

    @Override
    public DesktopExecSpec prepareExec(
            final Context context, final DesktopExecSpec exec) {
        if (exec == null || exec.backend != DesktopExecBackend.TERMUX) {
            return exec;
        }
        return exec.withCommand(
                TermuxX11StartupCommand.startOrReconnect(exec.command));
    }
}
