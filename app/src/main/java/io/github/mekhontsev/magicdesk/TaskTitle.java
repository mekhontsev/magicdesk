package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Shared task label for overview and application pickers. */
final class TaskTitle {
    private TaskTitle() { }

    static String resolve(final Context context, final AppItem app,
            final TaskRepository.TaskEntry task) {
        final BuiltInWindowRegistry.Presentation presentation = BuiltInWindowRegistry.presentation(task);
        if (presentation != null) return presentation.title();
        final BuiltInDesktopAppCatalog.Entry builtIn = BuiltInDesktopAppCatalog.find(task);
        final String fallback = builtIn != null ? context.getString(builtIn.fallbackLabelResId)
                : app != null ? app.label : task.packageName;
        final ConsoleTerminalRegistry.Snapshot terminal = ConsoleTerminalRegistry.snapshotForTask(task.taskId);
        return terminal == null ? fallback : terminal.taskLabel("termux".equals(terminal.backend)
                ? context.getString(R.string.console_termux_title) : fallback);
    }

    static String detail(final Context context, final TaskRepository.TaskEntry task) {
        final DisplayPresentations.Session viewer = DisplayPresentations.forTask(task.taskId);
        if (viewer != null) {
            final DesktopDisplayInfo source = viewer.source;
            return context.getString(R.string.independent_viewer_source, source.name, source.id);
        }
        final ConsoleTerminalRegistry.Snapshot terminal = ConsoleTerminalRegistry.snapshotForTask(task.taskId);
        return terminal == null ? "" : terminal.workingDirectory;
    }
}
