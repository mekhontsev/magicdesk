package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Joins window and session catalogs; it owns neither and does not infer ownership from process names. */
final class TaskManagerApplications {
    sealed interface Target permits AndroidApp, Terminal, Tmux, X11 { }
    record AndroidApp(TaskRepository.TaskEntry task) implements Target { }
    record Terminal(ConsoleTerminalRegistry.Snapshot session) implements Target { }
    record Tmux(TmuxSessionProvider.Session session) implements Target { }
    record X11(X11Sessions.Session session) implements Target { }
    record Entry(String id, String title, String detail, String packageName, Target target,
            List<TaskRepository.TaskEntry> windows, Set<SystemProcessSnapshot.Identity> processes) { }

    static List<Entry> collect(Context context, List<TaskRepository.TaskEntry> tasks,
            List<ConsoleTerminalRegistry.Snapshot> terminals, TmuxSessionProvider.Snapshot tmux,
            SystemMonitorRepository.Snapshot monitor, int ownTask) {
        final var profile = AppProfile.current(context);
        final var visible = tasks.stream().filter(t -> t.taskId != ownTask && profile.owns(t.userId)
                && DesktopManagedTaskPolicy.isControllableApplicationTask(t)).toList();
        final var processes = new ProcessCatalog(monitor.processes());
        final var endpoint = TermuxIntegration.inspect(context);
        final var result = new ArrayList<Entry>();
        final Set<Integer> claimed = new HashSet<>();
        for (var item : TerminalSessions.merge(terminals, tmux)) {
            final var terminal = item.terminal();
            final var server = item.tmux();
            final var ids = new HashSet<Integer>();
            final var pids = new HashSet<Integer>();
            if (server != null && tmux != null) {
                pids.addAll(tmux.panes.getOrDefault(server.id, Set.of()));
                for (var t : terminals) if (TerminalSessions.belongsTo(t, server, tmux)) ids.add(t.taskId);
            } else if (terminal != null) {
                ids.add(terminal.taskId);
                if (terminal.processId > 0 && terminal.processId <= Integer.MAX_VALUE) pids.add((int) terminal.processId);
            }
            final var windows = visible.stream().filter(t -> ids.contains(t.taskId)).toList();
            for (var window : windows) claimed.add(window.taskId);
            final boolean termux = server != null || terminal != null && "termux".equals(terminal.backend);
            final int uid = termux ? endpoint.uid : ShellAccess.currentSnapshot().uid;
            final String title = server != null ? server.name : terminal.taskLabel(context.getString(
                    termux ? R.string.console_termux_title : R.string.console_title));
            result.add(new Entry(server != null ? "tmux:" + server.id + ":" + server.createdSeconds : terminal.id,
                    title, server != null ? "tmux | " + server.windows + " windows | " + server.attachedClients + " clients"
                            : terminal.backend + " | " + terminal.workingDirectory,
                    termux ? endpoint.packageName : context.getPackageName(),
                    server != null ? new Tmux(server) : new Terminal(terminal), windows,
                    processes.descendants(pids, uid)));
        }
        for (var session : X11Sessions.list()) {
            if (session.stopped()) continue;
            final var ids = session.hostTaskIds();
            final var windows = visible.stream().filter(t -> ids.contains(t.taskId)).toList();
            for (var window : windows) claimed.add(window.taskId);
            // Only the server has a published process identity; client totals remain separate processes.
            final var server = processes.matching(p -> p.uid == session.endpoint.uid && p.name.equals(session.id()));
            result.add(new Entry(session.id(), session.name,
                    "X11 " + session.display() + " | " + session.state().name().toLowerCase(java.util.Locale.ROOT)
                            + " | " + context.getString(R.string.task_manager_server_resources),
                    session.endpoint.packageName, new X11(session), windows, server));
        }
        for (var task : visible) {
            if (claimed.contains(task.taskId)) continue;
            String label = task.packageName;
            int uid = -1;
            try {
                final var info = context.getPackageManager().getApplicationInfo(task.packageName, 0);
                label = context.getPackageManager().getApplicationLabel(info).toString(); uid = info.uid;
            } catch (PackageManager.NameNotFoundException ignored) { }
            final int owner = uid;
            final String title = TaskTitle.resolve(context, null, task);
            result.add(new Entry("task:" + task.userId + ":" + task.taskId,
                    title.equals(task.packageName) ? label : title, task.packageName + " | " + task.windowingMode,
                    task.packageName, new AndroidApp(task), List.of(task),
                    processes.matching(p -> p.uid == owner && (p.name.equals(task.packageName)
                            || p.name.startsWith(task.packageName + ":")))));
        }
        result.sort(java.util.Comparator.comparing(Entry::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id));
        return List.copyOf(result);
    }
    private TaskManagerApplications() { }
}
