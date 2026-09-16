package io.github.mekhontsev.magicdesk;

/** A selectable Start item, independent of its host, search query and destination. */
final class StartMenuEntry {
    enum Kind { APP, DESKTOP_APPLICATION, BUILT_IN, TASK, TERMINALS, ACTION, FILE }
    enum Action { SHOW_DESKTOP, SCREENSHOT, SCREEN_RECORDING }

    final Kind kind;
    final String label;
    final String detail;
    final AppItem app;
    final DesktopApplicationRepository.Entry desktopApplication;
    final BuiltInDesktopAppCatalog.Entry builtIn;
    final TaskRepository.TaskEntry task;
    final Action action;
    final ShellFileInfo file;
    final RecentApplicationStore.Entry recent;

    private StartMenuEntry(Kind kind, String label, String detail, AppItem app,
            DesktopApplicationRepository.Entry desktopApplication,
            BuiltInDesktopAppCatalog.Entry builtIn, TaskRepository.TaskEntry task,
            Action action, ShellFileInfo file) {
        this(kind, label, detail, app, desktopApplication, builtIn, task, action, file, null);
    }

    private StartMenuEntry(Kind kind, String label, String detail, AppItem app,
            DesktopApplicationRepository.Entry desktopApplication,
            BuiltInDesktopAppCatalog.Entry builtIn, TaskRepository.TaskEntry task,
            Action action, ShellFileInfo file, RecentApplicationStore.Entry recent) {
        this.kind = kind;
        this.label = label;
        this.detail = detail;
        this.app = app;
        this.desktopApplication = desktopApplication;
        this.builtIn = builtIn;
        this.task = task;
        this.action = action;
        this.file = file;
        this.recent = recent;
    }

    static StartMenuEntry recent(RecentApplicationStore.Entry recent, java.util.List<AppItem> apps) {
        final var shortcut = recent.shortcut();
        final AppItem app = shortcut.defaultLaunch ? LauncherAppRepository.find(apps,
                AppReference.forTarget(shortcut.application, shortcut.launchTarget)) : null;
        final String detail = shortcut.launchTarget != null ? shortcut.launchTarget.packageName
                : shortcut.execBackend == DesktopExecBackend.X11 ? "Termux" : shortcut.execBackend.wireName;
        return new StartMenuEntry(Kind.DESKTOP_APPLICATION, shortcut.name, detail, app,
                new DesktopApplicationRepository.Entry(shortcut, recent.sourcePath(), null),
                null, null, null, null, recent);
    }

    static StartMenuEntry app(AppItem app) {
        return new StartMenuEntry(Kind.APP, app.label, app.packageName,
                app, null, null, null, null, null);
    }

    static StartMenuEntry desktopApplication(DesktopApplicationRepository.Entry entry) {
        return new StartMenuEntry(Kind.DESKTOP_APPLICATION, entry.shortcut.name,
                entry.shortcut.execBackend == DesktopExecBackend.X11 ? "Termux" :
                        entry.shortcut.execBackend.wireName + ": " + entry.shortcut.exec,
                null, entry, null, null, null, null);
    }

    static StartMenuEntry builtIn(String label, BuiltInDesktopAppCatalog.Entry entry) {
        return new StartMenuEntry(Kind.BUILT_IN, label, "MagicDesk",
                null, null, entry, null, null, null);
    }

    static StartMenuEntry task(AppItem app, TaskRepository.TaskEntry task, String label, String detail) {
        return new StartMenuEntry(Kind.TASK, label, detail,
                app, null, null, task, null, null);
    }

    static StartMenuEntry terminals(String label) {
        return new StartMenuEntry(Kind.TERMINALS, label, "MagicDesk",
                null, null, null, null, null, null);
    }

    static StartMenuEntry action(String label, Action action) {
        return new StartMenuEntry(Kind.ACTION, label, "Action",
                null, null, null, null, action, null);
    }

    static StartMenuEntry file(ShellFileInfo file) {
        return new StartMenuEntry(Kind.FILE, file.name, file.absolutePath,
                null, null, null, null, null, file);
    }

    String stableKey() {
        if (recent != null) return "recent|" + recent.key();
        if (task != null) { return "task|" + task.userId + "|" + task.taskId; }
        if (app != null) { return "app|" + app.identity.persistentKey() + "|" + app.launchTarget.stableKey(); }
        if (desktopApplication != null) { return "command|" + desktopApplication.desktopFilePath; }
        if (builtIn != null) { return "builtin|" + builtIn.launchTarget.stableKey(); }
        if (action != null) { return "action|" + action.name(); }
        if (file != null) { return "file|" + file.absolutePath; }
        return "terminals";
    }
}
