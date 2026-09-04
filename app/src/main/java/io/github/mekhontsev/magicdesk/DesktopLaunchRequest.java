package io.github.mekhontsev.magicdesk;

/** Immutable description shared by every Desktop Entry launch surface. */
final class DesktopLaunchRequest {
    final String name;
    final String icon;
    final AndroidLaunchSpec androidLaunch;
    final AndroidShortcutSpec androidShortcut;
    final DesktopExecSpec exec;
    final DesktopLaunchPresentation presentation;
    final DesktopLaunchArguments arguments;
    final String desktopFilePath;

    DesktopLaunchRequest(
            final String name,
            final String icon,
            final AndroidLaunchSpec androidLaunch,
            final AndroidShortcutSpec androidShortcut,
            final DesktopExecSpec exec,
            final DesktopLaunchPresentation presentation,
            final DesktopLaunchArguments arguments,
            final String desktopFilePath) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("missing launch request name");
        }
        if (androidLaunch == null && androidShortcut == null && exec == null) {
            throw new IllegalArgumentException("empty desktop launch request");
        }
        if (androidLaunch != null && androidShortcut != null) {
            throw new IllegalArgumentException(
                    "ambiguous Android launch request");
        }
        this.name = name.trim();
        this.icon = icon == null ? "" : icon;
        this.androidLaunch = androidLaunch;
        this.androidShortcut = androidShortcut;
        this.exec = exec;
        this.presentation = presentation == null
                ? DesktopLaunchPresentation.automatic() : presentation;
        this.arguments = arguments == null
                ? DesktopLaunchArguments.empty() : arguments;
        this.desktopFilePath = desktopFilePath == null
                ? "" : desktopFilePath;
    }

    static DesktopLaunchRequest from(
            final DesktopApplicationShortcut shortcut) {
        return from(
                shortcut, DesktopLaunchArguments.empty(), "");
    }

    static DesktopLaunchRequest from(
            final DesktopApplicationShortcut shortcut,
            final DesktopLaunchArguments arguments,
            final String desktopFilePath) {
        if (shortcut == null) {
            throw new IllegalArgumentException("missing desktop shortcut");
        }
        final AndroidLaunchSpec androidLaunch;
        final AndroidShortcutSpec androidShortcut;
        final DesktopExecSpec exec;
        if (shortcut.defaultLaunch) {
            androidLaunch = AndroidLaunchSpec.defaultLaunch(
                    shortcut.launchTarget);
            androidShortcut = null;
            exec = null;
        } else if (shortcut.hasAppShortcutLaunch()) {
            androidLaunch = null;
            androidShortcut = new AndroidShortcutSpec(
                    shortcut.launchTarget, shortcut.appShortcutId);
            exec = null;
        } else if (shortcut.hasIntentLaunch()) {
            androidLaunch = AndroidLaunchSpec.intent(
                    shortcut.launchTarget, shortcut.intentUri);
            androidShortcut = null;
            exec = null;
        } else {
            androidLaunch = shortcut.launchTarget == null
                    ? null : AndroidLaunchSpec.defaultLaunch(
                            shortcut.launchTarget);
            androidShortcut = null;
            exec = new DesktopExecSpec(
                    shortcut.execBackend,
                    shortcut.exec,
                    shortcut.terminal,
                    shortcut.workingDirectory);
        }
        return new DesktopLaunchRequest(
                shortcut.name,
                shortcut.icon,
                androidLaunch,
                androidShortcut,
                exec,
                DesktopLaunchPresentation.forMode(shortcut.launchMode),
                arguments,
                desktopFilePath);
    }

    DesktopLaunchRequest withExec(final DesktopExecSpec value) {
        return new DesktopLaunchRequest(
                name,
                icon,
                androidLaunch,
                androidShortcut,
                value,
                presentation,
                arguments,
                desktopFilePath);
    }

    DesktopLaunchRequest withAndroidLaunch(
            final AndroidLaunchSpec value) {
        return new DesktopLaunchRequest(
                name,
                icon,
                value,
                null,
                exec,
                presentation,
                arguments,
                desktopFilePath);
    }

    DesktopLaunchRequest prepareExec() {
        if (exec == null) {
            return this;
        }
        return withExec(exec.withCommand(DesktopExecTemplate.expand(
                exec.command,
                arguments,
                name,
                icon,
                desktopFilePath)));
    }
}
