package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.List;

/** Declarative Termux command paired with the Termux:X11 Android viewer. */
final class TermuxX11LaunchIntegration
        implements DesktopLaunchIntegration {
    private static final String ACTION_RECONNECT = "reconnect";
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

    @Override
    public List<DesktopLaunchIntegrationAction> actions(
            final Context context) {
        final boolean directDisplay = !TermuxX11StartupCommand
                .requestedDisplay(
                        MagicDeskSettings.load().termuxX11StartupCommand)
                .isEmpty();
        return List.of(new DesktopLaunchIntegrationAction(
                ACTION_RECONNECT,
                R.string.action_termux_x11_reconnect,
                directDisplay && TermuxIntegration.isAvailable(context)));
    }

    @Override
    public void invokeAction(
            final Context context,
            final String actionId,
            final ActionCallback callback) {
        if (!ACTION_RECONNECT.equals(actionId)) {
            if (callback != null) {
                callback.onComplete(false, "unknown Termux:X11 action");
            }
            return;
        }
        TermuxX11RuntimeStatus.reconnect(
                context,
                result -> {
                    if (callback != null) {
                        callback.onComplete(
                                result.success, result.message);
                    }
                });
    }
}
