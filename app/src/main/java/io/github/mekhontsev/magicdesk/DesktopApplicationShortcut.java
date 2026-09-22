package io.github.mekhontsev.magicdesk;

/** Type=Application entry with an Android or command launch descriptor. */
final class DesktopApplicationShortcut extends DesktopEntry {
    final AppLaunchTarget launchTarget;
    final AppIdentity application;
    final String intentUri;
    final String appShortcutId;
    final DesktopLaunchMode launchMode;
    final boolean defaultLaunch;
    final DesktopExecBackend execBackend;
    final boolean terminal;
    final String workingDirectory;
    final DesktopMimeTypes mimeTypes;
    final GraphicalLaunchOptions graphics;
    final boolean literalExec;

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
        this(name, icon, exec, launchTarget, intentUri, launchMode,
                defaultLaunch, execBackend, terminal, workingDirectory,
                mimeTypes, appShortcutId, null, null, false);
    }

    private DesktopApplicationShortcut(
            final String name, final String icon, final String exec,
            final AppLaunchTarget launchTarget, final String intentUri,
            final DesktopLaunchMode launchMode, final boolean defaultLaunch,
            final DesktopExecBackend execBackend, final boolean terminal,
            final String workingDirectory, final DesktopMimeTypes mimeTypes,
            final String appShortcutId, final AppIdentity application, final GraphicalLaunchOptions graphics,
            final boolean literalExec) {
        super(name, icon, exec);
        if (application != null && (launchTarget == null
                || !application.packageName.equals(launchTarget.packageName))) {
            throw new IllegalArgumentException("shortcut application mismatch");
        }
        this.application = application;
        this.graphics = graphics;
        this.literalExec = graphics != null || literalExec;
        final String normalizedShortcutId = appShortcutId == null
                ? "" : appShortcutId.trim();
        if ((intentUri == null || intentUri.isEmpty())
                && normalizedShortcutId.isEmpty()
                && this.exec.isEmpty() && !(defaultLaunch && launchTarget != null)) {
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
        if (graphics != null && (!hasExecLaunch() || terminal))
            throw new IllegalArgumentException("Graphical presentation requires a graphical command");
    }

    boolean hasIntentLaunch() {
        return !intentUri.isEmpty();
    }

    static DesktopApplicationShortcut forApp(final AppItem app) {
        return new DesktopApplicationShortcut(app.label, app.packageName, "", app.launchTarget, "",
                DesktopLaunchMode.AUTO, true, DesktopExecBackend.SHELL, false).withApplication(app.identity);
    }

    DesktopApplicationShortcut withApplication(final AppIdentity identity) {
        return new DesktopApplicationShortcut(name, icon, exec, launchTarget,
                intentUri, launchMode, defaultLaunch, execBackend, terminal,
                workingDirectory, mimeTypes, appShortcutId, identity, graphics, literalExec);
    }

    DesktopApplicationShortcut withGraphics(GraphicalLaunchOptions value) {
        return new DesktopApplicationShortcut(name, icon, exec, launchTarget,
                intentUri, launchMode, defaultLaunch, execBackend, terminal,
                workingDirectory, mimeTypes, appShortcutId, application, value, value != null || literalExec);
    }

    DesktopApplicationShortcut withLiteralExec(boolean value) {
        return new DesktopApplicationShortcut(name, icon, exec, launchTarget,
                intentUri, launchMode, defaultLaunch, execBackend, terminal,
                workingDirectory, mimeTypes, appShortcutId, application, graphics, value);
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
