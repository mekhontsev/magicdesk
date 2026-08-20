package io.github.mekhontsev.magicdesk;

/** Immutable description shared by every Desktop Entry launch surface. */
final class DesktopLaunchRequest {
    final String name;
    final String icon;
    final AndroidLaunchSpec androidLaunch;
    final DesktopExecSpec exec;
    final DesktopLaunchMode launchMode;

    DesktopLaunchRequest(
            final String name,
            final String icon,
            final AndroidLaunchSpec androidLaunch,
            final DesktopExecSpec exec,
            final DesktopLaunchMode launchMode) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("missing launch request name");
        }
        if (androidLaunch == null && exec == null) {
            throw new IllegalArgumentException("empty desktop launch request");
        }
        this.name = name.trim();
        this.icon = icon == null ? "" : icon;
        this.androidLaunch = androidLaunch;
        this.exec = exec;
        this.launchMode = launchMode == null
                ? DesktopLaunchMode.AUTO : launchMode;
    }

    static DesktopLaunchRequest from(
            final DesktopApplicationShortcut shortcut) {
        if (shortcut == null) {
            throw new IllegalArgumentException("missing desktop shortcut");
        }
        final AndroidLaunchSpec androidLaunch;
        final DesktopExecSpec exec;
        if (shortcut.defaultLaunch) {
            androidLaunch = AndroidLaunchSpec.defaultLaunch(
                    shortcut.launchTarget);
            exec = null;
        } else if (shortcut.hasIntentLaunch()) {
            androidLaunch = AndroidLaunchSpec.intent(
                    shortcut.launchTarget, shortcut.intentUri);
            exec = null;
        } else {
            androidLaunch = shortcut.launchTarget == null
                    ? null : AndroidLaunchSpec.defaultLaunch(
                            shortcut.launchTarget);
            exec = new DesktopExecSpec(
                    shortcut.execBackend,
                    shortcut.exec,
                    shortcut.terminal);
        }
        return new DesktopLaunchRequest(
                shortcut.name,
                shortcut.icon,
                androidLaunch,
                exec,
                shortcut.launchMode);
    }

    DesktopLaunchRequest withExec(final DesktopExecSpec value) {
        return new DesktopLaunchRequest(
                name, icon, androidLaunch, value, launchMode);
    }

    DesktopLaunchRequest withAndroidLaunch(
            final AndroidLaunchSpec value) {
        return new DesktopLaunchRequest(
                name, icon, value, exec, launchMode);
    }
}
