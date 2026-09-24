package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Adapter from the existing Desktop publication and action gateway, without its own observer. */
final class DesktopShellTasks implements AutoCloseable {
    private final DesktopShellActivity host;
    final ShellTaskCatalog catalog = new ShellTaskCatalog(this::request);
    private TaskRepository.Snapshot tasks;

    DesktopShellTasks(DesktopShellActivity host) { this.host = host; }

    void update(TaskRepository.Snapshot snapshot) {
        tasks = snapshot;
        if (snapshot == null || !snapshot.available) { catalog.update(List.of(), false); return; }
        catalog.update(snapshot.tasks.stream().filter(host::isTaskbarTask).map(task -> {
            var app = LauncherAppRepository.findApplication(host.getLauncherApps(), host.appProfile().application(task));
            return new ShellTaskCatalog.Task(task.taskId, task.userId + ":" + task.packageName + ":" + task.componentName,
                    bounded(TaskTitle.resolve(host, app, task), 512), task.packageName, task.active, task.isFullscreen(), maximized(task),
                    MagicDeskRuntime.isTaskConcealed(host.getCurrentDisplayId(), task.taskId));
        }).toList(), true);
    }

    private static String bounded(String value, int size) {
        return value.length() <= size ? value : value.substring(0, size);
    }

    private boolean maximized(TaskRepository.TaskEntry task) {
        var layout = host.panels().shellScope().snapshot();
        if (!task.isFreeform() || layout == null) return false;
        var work = layout.workArea();
        return task.bounds.equals(new android.graphics.Rect(work.left(), work.top(), work.right(), work.bottom()));
    }

    private void request(ShellTaskCatalog.Task target, ShellTaskCatalog.Action action) {
        if (tasks == null || !tasks.available) return;
        var task = tasks.tasks.stream().filter(item -> item.taskId == target.taskId()).findFirst().orElse(null);
        int display = host.getCurrentDisplayId();
        if (task == null || task.displayId != display || !DesktopRuntimeBridge.hasWorkspace(display)) return;
        host.panels().releaseShellKeyboard();
        TaskRepository.ActionCallback completed = result -> {
            if (result == null || !result.success) CompatibilityDiagnostics.record("SHELL-ACTION-001",
                    "External panel action failed", action + ": " + (result == null ? "missing result" : result.message));
        };
        switch (action) {
            case ACTIVATE -> MagicDeskRuntime.focusDesktopTask(display, task.taskId, completed);
            case MINIMIZE -> MagicDeskRuntime.concealTask(display, task.taskId, completed);
            case UNMINIMIZE -> {
                if (MagicDeskRuntime.isTaskConcealed(display, task.taskId))
                    MagicDeskRuntime.focusDesktopTask(display, task.taskId, completed);
            }
            case CLOSE -> MagicDeskRuntime.closeTask(task, completed);
            case MAXIMIZE -> MagicDeskRuntime.setMaximized(display, task.taskId,
                    io.github.mekhontsev.magicdesk.hosted.HostedMaximization.BOTH, completed);
            case FULLSCREEN -> { if (!task.isFullscreen()) MagicDeskRuntime.makeTaskFullscreen(task, completed); }
            case UNMAXIMIZE -> MagicDeskRuntime.setMaximized(display, task.taskId,
                    io.github.mekhontsev.magicdesk.hosted.HostedMaximization.NONE, completed);
            case UNFULLSCREEN -> { if (task.isFullscreen()) MagicDeskRuntime.arrangeTask(display, task.taskId, DesktopTaskController.SHORTCUT_RESTORE); }
        }
    }

    @Override public void close() { tasks = null; catalog.close(); }
}
