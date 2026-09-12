package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** One session catalog; tmux owns its programs, the registry owns only its clients. */
final class TerminalSessions {
    private TerminalSessions() { }

    record Item(ConsoleTerminalRegistry.Snapshot terminal, TmuxSessionProvider.Session tmux) { }

    static List<Item> merge(List<ConsoleTerminalRegistry.Snapshot> terminals, TmuxSessionProvider.Snapshot tmux) {
        final List<Item> result = new ArrayList<>();
        final HashSet<String> merged = new HashSet<>();
        if (tmux != null && tmux.available) {
            for (final var session : tmux.sessions) {
                ConsoleTerminalRegistry.Snapshot selected = null;
                for (final var terminal : terminals) {
                    if (!belongsTo(terminal, session, tmux)) continue;
                    merged.add(terminal.id);
                    if (selected == null || terminal.focused || selected.taskId < 0 && terminal.taskId >= 0) selected = terminal;
                }
                result.add(new Item(selected, session));
            }
        }
        for (final var terminal : terminals) {
            if (!merged.contains(terminal.id)) result.add(new Item(terminal, null));
        }
        return result;
    }

    private static boolean belongsTo(ConsoleTerminalRegistry.Snapshot terminal,
            TmuxSessionProvider.Session session, TmuxSessionProvider.Snapshot tmux) {
        if (terminal.tmuxSessionId.isEmpty()) return false;
        final String current = tmux.clients.get(terminal.processId);
        // tmux may switch this client to a different session. Its live PID mapping wins.
        if (current != null) return current.equals(session.id);
        return !terminal.ready && terminal.processId <= 0 && terminal.tmuxSessionId.equals(session.id)
                && terminal.tmuxCreatedSeconds == session.createdSeconds;
    }

    static Intent tmuxIntent(Context context, TmuxSessionProvider.Session session, TmuxSessionProvider.Snapshot snapshot) {
        for (final Item item : merge(ConsoleTerminalRegistry.list(), snapshot)) {
            if (item.tmux != null && item.tmux.id.equals(session.id) && item.terminal != null) {
                return CommandConsoleActivity.attachIntent(context, item.terminal);
            }
        }
        return CommandConsoleActivity.createTmuxIntent(context, session);
    }

    static void open(Context context, Intent intent, ToolLaunchTarget target, String uniqueId,
            BuiltInWindowLauncher.Callback callback) {
        final String id = CommandConsoleActivity.terminalId(intent);
        final var session = id == null ? null : ConsoleTerminalRegistry.status(id);
        if (session != null && session.taskId >= 0 && session.displayId == target.displayId) {
            try {
                if (uniqueId != null) DesktopDisplayCatalog.require(target.displayId, uniqueId);
                if (target.desktop != DesktopRuntimeBridge.hasWorkspace(target.displayId)) {
                    throw new IOException("display ownership changed");
                }
                if (target.desktop) {
                    MagicDeskRuntime.focusDesktopTask(target.displayId, session.taskId,
                            result -> callback.onComplete(result.success ? null : new IOException(result.message)));
                    return;
                }
                final ActivityManager manager = context.getSystemService(ActivityManager.class);
                for (final var task : manager.getAppTasks()) {
                    if (task.getTaskInfo().taskId == session.taskId) {
                        task.moveToFront();
                        callback.onComplete(null);
                        return;
                    }
                }
            } catch (IOException | RuntimeException error) { callback.onComplete(error); return; }
        }
        ToolApplications.open(context, intent, target, uniqueId, callback);
    }
}
