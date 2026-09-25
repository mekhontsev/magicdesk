package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Protocol-neutral session controls; native catalogs and lifetimes remain with their backends. */
final class GraphicalSessions {
    record Application(Session session, long window) { }
    record Identity(DesktopExecBackend backend, int executorUid, int serverUid) { }
    record Control(long serial, boolean requestedFullscreen, Boolean actualFullscreen,
            long maximizeSerial, io.github.mekhontsev.magicdesk.hosted.HostedMaximization requestedMaximization,
            io.github.mekhontsev.magicdesk.hosted.HostedMaximization actualMaximization) { }
    record Window(long id, String title, boolean mapped, String appId, String role,
            io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout layout, Control control) { }
    interface Session {
        String id();
        String name();
        GraphicalProtocol protocol();
        Identity identity();
        default Map<String, Object> details() { return Map.of(); }
        default Map<String, Object> windowDetails(long window) { return Map.of(); }
        String state();
        String error();
        boolean ready();
        boolean stopped();
        boolean canExecute();
        List<Window> windows();
        List<HostedWindowPresentation.Observation> hosts();
        void closeWindow(long window, boolean force);
        void detachViewer(int taskId);
        void listen(Runnable listener);
        void unlisten(Runnable listener);
        void execute(String command, String directory);
        void close();
        Intent windowIntent(Context context, long window);
        int hostTaskId(long window);
        void recordUse(long window, RecentLaunchScope scope);
        default boolean desktop() { return false; }
        default boolean canIntegrateShell() { return false; }
        default AutoCloseable bindShell(DesktopShellActivity host, java.util.function.Consumer<String> ended) {
            throw new UnsupportedOperationException("Shell integration is unavailable for this session");
        }
        default void watch(Activity activity) { }
        default void unwatch(Activity activity) { }
        default boolean canScale() { return false; }
        default void scale(Activity activity) { throw new UnsupportedOperationException("Interface scaling is unavailable"); }
    }

    static Application findRecipe(String key) {
        var x11 = X11Sessions.findRecipe(key);
        if (x11 != null) return new Application(new X11(x11.session()), x11.window());
        for (var session : WaylandSessions.list()) {
            long window = session.recipeWindow(key);
            if (window >= 0) return new Application(new Wayland(session), window);
        }
        return null;
    }
    static Session start(Context context, GraphicalProtocol protocol, String name, String command, String directory,
            DesktopExecBackend backend, String keyboard, boolean desktop) {
        RuntimeCapabilities.current(context).require(context, backend == DesktopExecBackend.TERMUX
                ? RuntimeCapabilities.Service.TERMUX : RuntimeCapabilities.Service.SHELL);
        return switch (protocol) {
            case X11 -> {
                String cwd = DesktopExecWorkingDirectory.normalize(directory);
                String script = command == null || command.isBlank() ? "true" : command;
                if (!cwd.isEmpty()) script = "cd -- " + ShellCommandLine.quote(cwd) + " || exit\n" + script;
                yield new X11(X11Sessions.start(context, name, script, backend, keyboard));
            }
            case WAYLAND -> new Wayland(WaylandSessions.start(context, name, command, directory, backend, keyboard, null, desktop));
        };
    }
    static Session find(String id) {
        var x11 = X11Sessions.find(id);
        if (x11 != null) return new X11(x11);
        var wayland = WaylandSessions.find(id);
        return wayland == null ? null : new Wayland(wayland);
    }
    static List<Session> list() {
        List<Session> result = new ArrayList<>();
        for (var session : X11Sessions.list()) result.add(new X11(session));
        for (var session : WaylandSessions.list()) result.add(new Wayland(session));
        return List.copyOf(result);
    }
    static int count() { return X11Sessions.count() + WaylandSessions.count(); }
    static void changed(String sessionId, String operation) {
        try {
            DesktopAutomationEventJournal.record("graphics", operation, true, "", new org.json.JSONObject().put("sessionId", sessionId));
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    static void closeAll() { X11Sessions.closeAll(); WaylandSessions.closeAll(); }
    static void prepareForExit() { X11Sessions.prepareForExit(); WaylandSessions.prepareForExit(); }

    private static final class X11 implements Session {
        final X11Sessions.Session session;
        final Map<Runnable, X11Sessions.Listener> listeners = new LinkedHashMap<>();
        X11(X11Sessions.Session session) { this.session = session; }
        public String id() { return session.id(); }
        public String name() { return session.name; }
        public GraphicalProtocol protocol() { return GraphicalProtocol.X11; }
        public Identity identity() { return new Identity(session.execution.commands.backend, session.execution.commands.uid, session.execution.serverUid); }
        public Map<String, Object> details() {
            return Map.of("display", session.display(), "dpi", session.dpi(), "scalePercent", session.scalePercent(),
                    "application", session.application, "fileEnvironment", session.fileEnvironment());
        }
        public Map<String, Object> windowDetails(long id) {
            for (var window : session.windows()) if (window.id() == id) return Map.of("instance", window.instance(),
                    "className", window.className(), "hostManaged", window.management().managed());
            return Map.of();
        }
        public String state() { return session.state().name(); }
        public String error() { return session.error(); }
        public boolean ready() { return session.ready(); }
        public boolean stopped() { return session.stopped(); }
        public boolean canExecute() { return session.canExecuteHostCommand(); }
        public boolean desktop() { return true; }
        public boolean canIntegrateShell() { return !session.application; }
        public AutoCloseable bindShell(DesktopShellActivity host, java.util.function.Consumer<String> ended) {
            var binding = session.bindShell(host.panels().shellScope(), host.getResources().getDisplayMetrics().densityDpi, ended);
            binding.origin(id());
            try {
                binding.host(host.shellSurfaceHost(surface -> new X11SurfaceOutput(binding.openOutput(surface.id()))),
                        host.shellPresentation());
                return binding;
            } catch (RuntimeException error) { binding.close(); throw error; }
        }
        public boolean canScale() { return true; }
        public void scale(Activity activity) { X11ScaleDialog.show(activity, session); }
        public List<Window> windows() {
            return session.windows().stream().map(window -> {
                var control = window.management();
                return new Window(window.id(), window.title(), window.mapped(), window.className(),
                        window.role().name().toLowerCase(java.util.Locale.ROOT), session.layout(window.id()),
                        new Control(Integer.toUnsignedLong(control.request().serial()), control.request().fullscreen(), control.actual().fullscreen(),
                                Integer.toUnsignedLong(control.maximization().serial()), control.maximization().requested(), control.maximization().actual()));
            }).toList();
        }
        public List<HostedWindowPresentation.Observation> hosts() { return session.presentation.observations(); }
        public void closeWindow(long window, boolean force) { session.closeWindow(window, force); }
        public void detachViewer(int taskId) { session.presentation.detachViewer(taskId); }
        public void listen(Runnable listener) {
            if (listeners.containsKey(listener)) return;
            X11Sessions.Listener adapter = listener::run;
            listeners.put(listener, adapter); session.listen(adapter);
        }
        public void unlisten(Runnable listener) { var adapter = listeners.remove(listener); if (adapter != null) session.unlisten(adapter); }
        public void execute(String command, String directory) {
            String cwd = DesktopExecWorkingDirectory.normalize(directory);
            session.execute(cwd.isEmpty() ? command : "cd -- " + ShellCommandLine.quote(cwd) + " || exit\n" + command);
        }
        public void close() { session.close(); }
        public Intent windowIntent(Context context, long window) { session.claimWindow(window); return X11Activity.windowIntent(context, session, window); }
        public int hostTaskId(long window) { return session.hostTaskId(window); }
        public void recordUse(long window, RecentLaunchScope scope) { session.recordUse(window, scope); }
    }
    private static final class Wayland implements Session {
        final WaylandSessions.Session session;
        final Map<Runnable, WaylandSessions.Listener> listeners = new LinkedHashMap<>();
        Wayland(WaylandSessions.Session session) { this.session = session; }
        public String id() { return session.id(); }
        public String name() { return session.name; }
        public GraphicalProtocol protocol() { return GraphicalProtocol.WAYLAND; }
        public Identity identity() { return new Identity(session.execution.commands.backend, session.execution.commands.uid, session.execution.serverUid); }
        public String state() { return session.state(); }
        public String error() { return session.error(); }
        public boolean ready() { return session.ready(); }
        public boolean stopped() { return session.stopped(); }
        public boolean canExecute() { return session.execution.canExecuteHostCommand(); }
        public boolean desktop() { return session.desktop; }
        public boolean canIntegrateShell() { return session.canIntegrateShell(); }
        public AutoCloseable bindShell(DesktopShellActivity host, java.util.function.Consumer<String> ended) {
            var binding = session.bindShell(host.panels().shellScope(), host.getResources().getDisplayMetrics().densityDpi,
                    new WaylandShellBinding.Listener() {
                        public void changed() { }
                        public void closed(String reason) { ended.accept(reason); }
                    });
            try {
                binding.host(host.shellSurfaceHost(surface -> new WaylandSurfaceOutput(
                        binding.openOutput(surface.id(), surface.bounds().width(), surface.bounds().height()))),
                        host.shellPresentation());
                binding.origin(id());
                binding.tasks(host.shellTasks());
                return binding;
            } catch (RuntimeException error) { binding.close(); throw error; }
        }
        public List<Window> windows() {
            return session.windows().stream().map(window -> new Window(window.id(), window.title(), window.mapped(),
                    window.appId(), "application", session.layout(window.id()), new Control(window.requestSerial(), window.fullscreen(), null,
                            window.maximizeSerial(), window.maximized() ? io.github.mekhontsev.magicdesk.hosted.HostedMaximization.BOTH
                            : io.github.mekhontsev.magicdesk.hosted.HostedMaximization.NONE, null))).toList();
        }
        public List<HostedWindowPresentation.Observation> hosts() { return session.presentation.observations(); }
        public void closeWindow(long window, boolean force) { session.closeWindow(window, force); }
        public void detachViewer(int taskId) { session.presentation.detachViewer(taskId); }
        public void listen(Runnable listener) {
            if (listeners.containsKey(listener)) return;
            WaylandSessions.Listener adapter = listener::run;
            listeners.put(listener, adapter); session.listen(adapter);
        }
        public void unlisten(Runnable listener) { var adapter = listeners.remove(listener); if (adapter != null) session.unlisten(adapter); }
        public void watch(Activity activity) { session.presentation.host(activity); }
        public void unwatch(Activity activity) { session.presentation.hostRemoved(activity, 0, false); }
        public void execute(String command, String directory) { session.execute(command, directory); }
        public void close() { session.close(); }
        public int hostTaskId(long window) { return session.hostTaskId(window); }
        public void recordUse(long window, RecentLaunchScope scope) { session.recordUse(scope); }
        public Intent windowIntent(Context context, long window) {
            if (window != 0 && !session.containsWindow(window)) throw new IllegalArgumentException("Select a live Wayland toplevel");
            session.presentation.claim(window);
            return session.windowIntent(context, window);
        }
    }

    private GraphicalSessions() { }
}
