package io.github.mekhontsev.magicdesk;

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

    static boolean belongsTo(ConsoleTerminalRegistry.Snapshot terminal,
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
        open(context, intent, target, uniqueId, DesktopLaunchPresentation.automatic(), callback);
    }

    static void open(Context context, Intent intent, ToolLaunchTarget target, String uniqueId,
            DesktopLaunchPresentation presentation, BuiltInWindowLauncher.Callback callback) {
        TaskCommandQueue.execute(() -> {
            try {
                target.requireCurrent(DesktopRuntimeBridge.workspaceDisplayIds());
                if (!target.desktop) OrdinaryActivityLaunch.requirePresentation(presentation);
                InteractiveActivityLaunch.requireDestination(context, target.displayId, uniqueId);
                final String id = CommandConsoleActivity.terminalId(intent);
                final var session = id == null ? null : ConsoleTerminalRegistry.status(id);
                if (session != null && session.taskId >= 0) {
                    final var local = target.desktop ? InteractiveActivityLaunch.OwnTaskResult.NEEDS_PLACEMENT
                            : InteractiveActivityLaunch.showOwnTask(context, session.taskId, target.displayId);
                    if (local == InteractiveActivityLaunch.OwnTaskResult.SHOWN) {
                        callback.onComplete(null);
                        return;
                    }
                    if (local == InteractiveActivityLaunch.OwnTaskResult.NEEDS_PLACEMENT) {
                        final var snapshot = TaskRepository.loadAllNow();
                        if (!snapshot.available) { throw new IOException(snapshot.error); }
                        for (final var task : snapshot.tasks) {
                            if (task.taskId == session.taskId
                                    && BuildConfig.APPLICATION_ID.equals(task.packageName)
                                    && AppProfile.current(context).owns(task.userId)) {
                                ApplicationTaskPlacement.place(task, target, uniqueId,
                                        presentation.withInstancePolicy(DesktopTaskInstancePolicy.REUSE_EXISTING),
                                        result -> callback.onComplete(result.success ? null : new IOException(result.message)));
                                return;
                            }
                        }
                    }
                }
                // A retained PTY without a window needs a new Activity, not an
                // unrelated console instance selected by Android's task affinity.
                ToolApplications.open(context, intent, target, uniqueId,
                        presentation.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW), callback);
            } catch (IOException | RuntimeException error) { callback.onComplete(error); }
        });
    }
}
