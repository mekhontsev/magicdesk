package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;
import java.io.IOException;
import java.util.function.Consumer;

/** UI actions use each resource owner's existing lifecycle, never package-wide substitutes. */
final class TaskManagerActions {
    private final Activity activity;
    private final Runnable refresh;
    private final Consumer<TaskManagerApplications.Entry> processes;
    TaskManagerActions(Activity activity, Runnable refresh, Consumer<TaskManagerApplications.Entry> processes) {
        this.activity = activity; this.refresh = refresh; this.processes = processes;
    }

    void open(TaskManagerApplications.Entry entry) {
        if (!entry.windows().isEmpty()) { chooseWindow(entry, false); return; }
        try {
            final var target = ToolLaunchTarget.resolve("auto", activity.getDisplay().getDisplayId(),
                    DesktopRuntimeBridge.workspaceDisplayIds());
            if (entry.target() instanceof TaskManagerApplications.Terminal terminal) {
                final var live = ConsoleTerminalRegistry.status(terminal.session().id);
                if (live == null) throw new IOException("Terminal session has ended");
                TerminalSessions.open(activity, CommandConsoleActivity.attachIntent(activity, live), target, null, this::result);
            } else if (entry.target() instanceof TaskManagerApplications.Tmux tmux) {
                TmuxSessionProvider.list(activity, (snapshot, error) -> activity.runOnUiThread(() -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return;
                    final var live = snapshot == null ? null : snapshot.find(tmux.session().id);
                    if (error != null || live == null || live.createdSeconds != tmux.session().createdSeconds) {
                        result(error != null ? error : new IOException("tmux session has ended")); return;
                    }
                    TerminalSessions.open(activity, TerminalSessions.tmuxIntent(activity, live, snapshot), target, null, this::result);
                }));
            } else if (entry.target() instanceof TaskManagerApplications.X11 x11) {
                if (X11Sessions.find(x11.session().id()) != x11.session() || x11.session().stopped())
                    throw new IOException("X11 session has ended");
                final Intent intent = X11Activity.createIntent(activity).putExtra(X11Activity.SESSION, x11.session().id())
                        .putExtra(X11Activity.APPLICATION, x11.session().application);
                ToolApplications.open(activity, intent, target, null,
                        DesktopLaunchPresentation.automatic().withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW), this::result);
            }
        } catch (IOException | RuntimeException error) { result(error); }
    }

    void menu(View anchor, TaskManagerApplications.Entry entry) {
        final PopupMenu menu = new PopupMenu(activity, anchor);
        add(menu, R.string.task_manager_focus, () -> open(entry));
        if (!entry.windows().isEmpty()) add(menu, R.string.task_manager_close, () -> chooseWindow(entry, true));
        if (!entry.processes().isEmpty()) add(menu, R.string.task_manager_processes, () -> processes.accept(entry));
        if (entry.target() instanceof TaskManagerApplications.AndroidApp app) {
            add(menu, R.string.task_manager_logs, () -> BuiltInWindowLauncher.launch(activity,
                    AppLogViewerActivity.createIntent(activity, app.task().packageName, entry.title()),
                    AppLogViewerActivity.launchTarget(), this::result));
            if (!activity.getPackageName().equals(app.task().packageName))
                add(menu, R.string.task_manager_force_stop, () -> confirm(R.string.task_manager_force_stop,
                        activity.getString(R.string.task_manager_force_stop_message, entry.title()),
                        () -> MagicDeskRuntime.forceStopApplication(AppProfile.current(activity).application(app.task()),
                                this::taskResult)));
        } else if (entry.target() instanceof TaskManagerApplications.Terminal terminal
                && !terminal.session().tmuxSessionId.isEmpty()) {
            add(menu, R.string.terminal_detach, () -> confirm(R.string.terminal_detach,
                    activity.getString(R.string.terminal_detach_tmux_confirm), () -> end(entry)));
        } else {
            add(menu, R.string.terminal_end_session, () -> confirm(R.string.terminal_end_session,
                    activity.getString(R.string.task_manager_end_session_confirm, entry.title()), () -> end(entry)));
        }
        menu.show();
    }

    private void end(TaskManagerApplications.Entry entry) {
        try {
            if (entry.target() instanceof TaskManagerApplications.Terminal terminal)
                ConsoleTerminalRegistry.close(terminal.session().id);
            else if (entry.target() instanceof TaskManagerApplications.Tmux tmux) {
                TmuxSessionProvider.end(activity, tmux.session(), this::result); return;
            } else if (entry.target() instanceof TaskManagerApplications.X11 x11) {
                if (X11Sessions.find(x11.session().id()) == x11.session()) x11.session().close();
            }
            refresh.run();
        } catch (RuntimeException error) { result(error); }
    }

    private void chooseWindow(TaskManagerApplications.Entry entry, boolean close) {
        if (entry.windows().size() == 1) { window(entry.windows().get(0), close); return; }
        final String[] names = entry.windows().stream().map(t -> TaskTitle.resolve(activity, null, t)
                + " [" + t.displayId + "] #" + t.taskId).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle(close ? R.string.task_manager_close : R.string.task_manager_focus)
                .setItems(names, (d, i) -> window(entry.windows().get(i), close))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void window(TaskRepository.TaskEntry task, boolean close) {
        if (close) { MagicDeskRuntime.closeTask(task, this::taskResult); return; }
        TaskCommandQueue.execute(() -> {
            try {
                final var live = ApplicationTaskPlacement.requireLive(task);
                if (ApplicationTaskPlacement.isManaged(live))
                    MagicDeskRuntime.focusDesktopTask(live.displayId, live.taskId, this::taskResult);
                else ApplicationTaskPlacement.controlIndependent(live, null, false, this::taskResult);
            } catch (IOException | RuntimeException error) { result(error); }
        });
    }

    void processMenu(View anchor, SystemProcessSnapshot process) {
        final PopupMenu menu = new PopupMenu(activity, anchor);
        add(menu, R.string.task_manager_process_details, () -> new AlertDialog.Builder(activity)
                .setTitle(process.name).setMessage("PID: " + process.pid + "\nPPID: " + process.parentPid
                        + "\nUID: " + process.uid + "\nState: " + process.state + "\nStart ticks: " + process.startTicks)
                .setPositiveButton(android.R.string.ok, null).show());
        if (ProcessControl.allowed(activity, process)) {
            add(menu, R.string.task_manager_end_process, () -> signal(process, false));
            add(menu, R.string.task_manager_kill_process, () -> signal(process, true));
        }
        menu.show();
    }

    private void signal(SystemProcessSnapshot process, boolean force) {
        confirm(force ? R.string.task_manager_kill_process : R.string.task_manager_end_process,
                activity.getString(R.string.task_manager_signal_confirm, process.name, process.pid),
                () -> ProcessControl.signal(activity, process, force, this::result));
    }
    private void confirm(int title, String message, Runnable action) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(title, (d, which) -> action.run()).show();
    }
    private void add(PopupMenu menu, int title, Runnable action) {
        menu.getMenu().add(title).setOnMenuItemClickListener(item -> { action.run(); return true; });
    }
    private void taskResult(TaskRepository.ActionResult result) {
        result(result.success ? null : new IOException(result.message));
    }
    private void result(Throwable error) {
        activity.runOnUiThread(() -> {
            if (activity.isDestroyed() || activity.isFinishing()) return;
            if (error != null) Toast.makeText(activity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
            refresh.run();
        });
    }
}
