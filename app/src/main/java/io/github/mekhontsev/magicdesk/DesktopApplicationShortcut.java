package io.github.mekhontsev.magicdesk;

/** Type=Application entry with an Android or command launch descriptor. */
final class DesktopApplicationShortcut extends DesktopEntry {
    final AppLaunchTarget launchTarget;
    final String intentUri;
    final String appShortcutId;
    final DesktopLaunchMode launchMode;
    final boolean defaultLaunch;
    final DesktopExecBackend execBackend;
    final boolean terminal;
    final String workingDirectory;
    final DesktopMimeTypes mimeTypes;

    DesktopApplicationShortcut(
            final String name,
            final String icon,
            final String exec,
            final AppLaunchTarget launchTarget,
            final String intentUri,
            final DesktopLaunchMode launchMode,
            final boolean defaultLaunch,
            final DesktopExecBackend execBackend,
            final boolean terminal) {
        this(
                name,
                icon,
                exec,
                launchTarget,
                intentUri,
                launchMode,
                defaultLaunch,
                execBackend,
                terminal,
                "");
    }

    DesktopApplicationShortcut(
            final String name,
            final String icon,
            final String exec,
            final AppLaunchTarget launchTarget,
            final String intentUri,
            final DesktopLaunchMode launchMode,
            final boolean defaultLaunch,
            final DesktopExecBackend execBackend,
            final boolean terminal,
            final String workingDirectory) {
        this(
                name,
                icon,
                exec,
                launchTarget,
                intentUri,
                launchMode,
                defaultLaunch,
                execBackend,
                terminal,
                workingDirectory,
                DesktopMimeTypes.empty(),
                "");
    }

    DesktopApplicationShortcut(
            final String name,
            final String icon,
            final String exec,
            final AppLaunchTarget launchTarget,
            final String intentUri,
            final DesktopLaunchMode launchMode,
            final boolean defaultLaunch,
            final DesktopExecBackend execBackend,
            final boolean terminal,
            final String workingDirectory,
            final DesktopMimeTypes mimeTypes) {
        this(
                name,
                icon,
                exec,
                launchTarget,
                intentUri,
                launchMode,
                defaultLaunch,
                execBackend,
                terminal,
                workingDirectory,
                mimeTypes,
                "");
    }

    DesktopApplicationShortcut(
            final String name,
            final String icon,
            final String exec,
            final AppLaunchTarget launchTarget,
            final String intentUri,
            final DesktopLaunchMode launchMode,
            final boolean defaultLaunch,
            final DesktopExecBackend execBackend,
            final boolean terminal,
            final String workingDirectory,
            final DesktopMimeTypes mimeTypes,
            final String appShortcutId) {
        super(name, icon, exec);
        final String normalizedShortcutId = appShortcutId == null
                ? "" : appShortcutId.trim();
        if ((intentUri == null || intentUri.isEmpty())
                && normalizedShortcutId.isEmpty()
                && this.exec.isEmpty()) {
            throw new IllegalArgumentException(
                    "application entry has no launch descriptor");
        }
        this.launchTarget = launchTarget;
        this.intentUri = intentUri == null ? "" : intentUri;
        this.appShortcutId = normalizedShortcutId;
        this.launchMode = launchMode == null
                ? DesktopLaunchMode.AUTO : launchMode;
        if (defaultLaunch && launchTarget == null) {
            throw new IllegalArgumentException(
                    "default launch requires an application target");
        }
        if (!this.appShortcutId.isEmpty() && launchTarget == null) {
            throw new IllegalArgumentException(
                    "app shortcut requires a publisher target");
        }
        this.defaultLaunch = defaultLaunch;
        this.execBackend = execBackend == null
                ? DesktopExecBackend.SHELL : execBackend;
        this.terminal = terminal;
        this.workingDirectory = DesktopExecWorkingDirectory.normalize(
                workingDirectory);
        this.mimeTypes = mimeTypes == null
                ? DesktopMimeTypes.empty() : mimeTypes;
        if (hasExecLaunch()) {
            DesktopExecCommand.normalize(this.exec);
        }
    }

    boolean hasIntentLaunch() {
        return !intentUri.isEmpty();
    }

    boolean hasAppShortcutLaunch() {
        return !appShortcutId.isEmpty();
    }

    boolean hasExecLaunch() {
        return !exec.isEmpty()
                && !hasIntentLaunch()
                && !hasAppShortcutLaunch()
                && !defaultLaunch;
    }

}
