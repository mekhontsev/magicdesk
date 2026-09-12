package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Shared task label for overview and application pickers. */
final class TaskTitle {
    private TaskTitle() { }

    static String resolve(final Context context, final AppItem app,
            final TaskRepository.TaskEntry task) {
        final BuiltInDesktopAppCatalog.Entry builtIn = BuiltInDesktopAppCatalog.find(task);
        final String fallback = builtIn == null ? app.label : context.getString(builtIn.fallbackLabelResId);
        final ConsoleTerminalRegistry.Snapshot terminal = ConsoleTerminalRegistry.snapshotForTask(task.taskId);
        return terminal == null ? fallback : terminal.taskLabel("termux".equals(terminal.backend)
                ? context.getString(R.string.console_termux_title) : fallback);
    }
}
