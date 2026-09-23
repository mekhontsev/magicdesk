package io.github.mekhontsev.magicdesk;

import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, revocable contributions to a live workspace; never owns the graphical session. */
final class GraphicalShells {
    record State(int displayId, String workspaceId, String error) { }
    private static final Map<String, Binding> bindings = new LinkedHashMap<>();
    private static final Set<Runnable> listeners = new LinkedHashSet<>();
    private static volatile Map<String, State> published = Map.of();

    static State state(String session) {
        return published.getOrDefault(session, new State(-1, "", ""));
    }

    static void select(GraphicalSessions.Session session, String workspaceId) {
        checkThread();
        if (workspaceId == null || workspaceId.isEmpty()) { release(session.id()); return; }
        if (!session.ready() || !session.canIntegrateShell())
            throw new IllegalStateException("This graphical session cannot contribute shell components");
        var selected = DesktopRuntimeBridge.getWorkspaces().stream()
                .filter(item -> item.workspace().id.equals(workspaceId)).findFirst().orElse(null);
        var workspace = selected == null ? null : DesktopRuntimeBridge.getWorkspaceRuntime(selected.activeWorkspaceDisplayId());
        var host = workspace == null ? null : workspace.host();
        if (host == null || workspace.isClosed()) throw new IllegalStateException("Select a ready Desktop workspace");
        var existing = bindings.get(session.id());
        if (existing != null && existing.state.workspaceId().equals(workspace.id) && existing.contribution != null) return;
        release(session.id());
        var binding = new Binding(session, workspace, host);
        bindings.put(session.id(), binding);
        session.listen(binding.sessionChanged);
        try {
            var contribution = session.bindShell(host, binding::ended);
            if (binding.ended) contribution.close(); else binding.contribution = contribution;
        } catch (Exception error) {
            binding.ended(ShellAccess.usefulMessage(error));
            throw new IllegalStateException(binding.state.error(), error);
        }
        changed();
    }

    static void release(String session) {
        checkThread();
        var binding = bindings.remove(session);
        if (binding != null) {
            binding.session.unlisten(binding.sessionChanged);
            binding.ended("");
        }
        changed();
    }

    static void releaseHost(DesktopShellActivity host) {
        checkThread();
        for (var binding : List.copyOf(bindings.values()))
            if (binding.host == host) release(binding.session.id());
    }

    static void listen(Runnable listener) { checkThread(); listeners.add(listener); }
    static void unlisten(Runnable listener) { checkThread(); listeners.remove(listener); }
    private static void changed() {
        var snapshot = new LinkedHashMap<String, State>();
        bindings.forEach((id, binding) -> snapshot.put(id, binding.state));
        published = Map.copyOf(snapshot);
        for (var listener : List.copyOf(listeners)) listener.run();
    }

    private static final class Binding {
        final GraphicalSessions.Session session;
        final Runnable sessionChanged;
        State state;
        AutoCloseable contribution;
        DesktopShellActivity host;
        boolean ended;

        Binding(GraphicalSessions.Session session, DesktopWorkspaceRuntime workspace, DesktopShellActivity host) {
            this.session = session;
            this.host = host;
            state = new State(workspace.displayId, workspace.id, "");
            sessionChanged = () -> {
                if (session.stopped()) release(session.id());
                else if (!session.ready()) ended("Graphical session is unavailable");
            };
        }

        void ended(String reason) {
            checkThread();
            if (ended) return;
            ended = true;
            host = null;
            state = new State(-1, "", reason == null ? "" : reason);
            var previous = contribution;
            contribution = null;
            if (previous != null) {
                try { previous.close(); }
                catch (Exception error) { state = new State(-1, "", ShellAccess.usefulMessage(error)); }
            }
            changed();
        }
    }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Shell integration requires main thread");
    }
    private GraphicalShells() { }
}
