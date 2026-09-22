package io.github.mekhontsev.magicdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import io.github.mekhontsev.magicdesk.wayland.IWaylandServer;
import io.github.mekhontsev.magicdesk.wayland.WaylandClientLaunch;
import io.github.mekhontsev.magicdesk.wayland.WaylandServer;
import io.github.mekhontsev.magicdesk.wayland.WaylandSession;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** Retained compositors and commands; Android hosts borrow their outputs. */
final class WaylandSessions {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService WORK = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "WaylandLifecycle"));
    private static final Map<String, Session> SESSIONS = new LinkedHashMap<>();

    interface Listener {
        void changed();
        default void frame(long output, int width, int height) { }
    }

    static Session start(Context context, String name, String command, String directory,
            DesktopExecBackend backend, String keyboard) {
        if (name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("Session name must contain 1 to 128 characters");
        Session session = new Session(context.getApplicationContext(), name.trim(), new WaylandExecution(context, backend, keyboard));
        synchronized (SESSIONS) { SESSIONS.put(session.id(), session); }
        MAIN.post(() -> session.start(command, directory));
        return session;
    }

    static Session find(String id) { synchronized (SESSIONS) { return SESSIONS.get(id); } }
    static List<Session> list() { synchronized (SESSIONS) { return List.copyOf(SESSIONS.values()); } }
    static int count() { synchronized (SESSIONS) { return SESSIONS.size(); } }
    static void closeAll() { for (var session : list()) session.close(); }
    static void prepareForExit() { for (var session : list()) session.presentation.close(); }

    static final class Session implements HostedWindowPresentation.Session, WaylandSession.Listener {
        final String name;
        final WaylandExecution execution;
        final HostedWindowPresentation presentation;
        private final Context context;
        private final OperationResources resources = new OperationResources();
        private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
        private final Map<Integer, Long> hosts = new LinkedHashMap<>();
        private volatile WaylandSession renderer;
        private volatile String state = "STARTING", error = "";
        private IBinder serverIdentity;
        private boolean registered;
        private final Runnable timeout = () -> fail(new IOException("Wayland server startup deadline expired"));
        private String startupCommand, startupDirectory;
        private String socket;
        private final BroadcastReceiver admission = new BroadcastReceiver() {
            @Override public void onReceive(Context source, Intent intent) {
                if (stopped() || getSentFromUid() != execution.serverUid
                        || !id().equals(intent.getStringExtra("session"))) return;
                String token = intent.getStringExtra("token");
                if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        execution.token.getBytes(StandardCharsets.UTF_8))) return;
                try {
                    var extras = intent.getExtras();
                    IBinder binder = extras == null ? null : extras.getBinder("server");
                    if (binder == null) throw new SecurityException("Missing Wayland server Binder");
                    String phase = intent.getStringExtra("phase");
                    if ("attach".equals(phase)) {
                        if (serverIdentity != null) throw new SecurityException("Wayland server already attached");
                        serverIdentity = binder;
                        renderer = new WaylandSession(IWaylandServer.Stub.asInterface(binder), execution.serverUid, Session.this);
                    } else if ("ready".equals(phase)) {
                        if (!binder.equals(serverIdentity) || renderer == null || !state.equals("STARTING"))
                            throw new SecurityException("Unknown Wayland readiness announcement");
                        socket = intent.getStringExtra("display");
                        if (socket == null || !socket.matches("wayland-[0-9]+"))
                            throw new SecurityException("Invalid Wayland socket name");
                        state = "READY";
                        MAIN.removeCallbacks(timeout);
                        unregister();
                        if (startupCommand != null && !startupCommand.isBlank()) execute(startupCommand, startupDirectory);
                        changed();
                    }
                } catch (android.os.RemoteException | RuntimeException failure) { fail(failure); }
            }
        };

        Session(Context context, String name, WaylandExecution execution) {
            this.context = context; this.name = name; this.execution = execution;
            presentation = new HostedWindowPresentation(context, this);
        }

        private void start(String command, String directory) {
            if (stopped()) return;
            startupCommand = command;
            startupDirectory = DesktopExecWorkingDirectory.normalize(directory);
            try {
                MagicDeskRuntime.startTools(context, false);
                context.registerReceiver(admission, new IntentFilter(WaylandServer.ACTION), null, MAIN, Context.RECEIVER_EXPORTED);
                registered = true;
                MAIN.postDelayed(timeout, 60_000);
                var process = resources.reserve();
                WORK.execute(() -> {
                    try {
                        if (stopped()) { process.close(); return; }
                        process.attach(execution.startServer((code, output, failure) -> {
                            process.close();
                            if (!stopped()) fail(failure != null ? failure : new IOException("Wayland server exited (" + code + "): " + output));
                        }));
                    } catch (IOException | RuntimeException failure) { process.close(); fail(failure); }
                });
            } catch (RuntimeException failure) { fail(failure); }
        }

        String id() { return execution.id; }
        String state() { return state; }
        String error() { return error; }
        boolean stopped() { return state.equals("CLOSED") || state.equals("FAILED"); }
        @Override public boolean ready() { return state.equals("READY") && renderer != null && !renderer.isClosed(); }
        List<WaylandSession.Window> windows() { var current = renderer; return current == null ? List.of() : current.windows(); }
        @Override public boolean containsWindow(long id) { return windows().stream().anyMatch(window -> window.id() == id); }
        void listen(Listener listener) { listeners.add(listener); }
        void unlisten(Listener listener) { listeners.remove(listener); }
        void host(int taskId, long window) { hosts.put(taskId, window); presentation.claim(window); }
        void releaseHost(int taskId) { hosts.remove(taskId); }
        @Override public int hostTaskId(long window) {
            return hosts.entrySet().stream().filter(entry -> entry.getValue() == window).map(Map.Entry::getKey).findFirst().orElse(-1);
        }
        @Override public Intent windowIntent(Context context, long window) { return WaylandActivity.windowIntent(context, this, window); }
        WaylandSession.Output openOutput(long window, int width, int height) {
            if (!ready()) throw new IllegalStateException("Wayland session is not ready");
            return renderer.openOutput(window, width, height);
        }
        void closeWindow(long window, boolean force) {
            if (!ready()) return;
            renderer.closeWindow(window, force);
        }

        void execute(String command, String directory) {
            if (command == null || command.isBlank()) throw new IllegalArgumentException("Missing Wayland command");
            if (!ready()) throw new IllegalStateException("Wayland session is not ready");
            String cwd = DesktopExecWorkingDirectory.normalize(directory);
            if (execution.commands.uid == execution.serverUid) {
                var commandSlot = resources.reserve();
                WORK.execute(() -> {
                    try {
                        if (stopped()) { commandSlot.close(); return; }
                        commandSlot.attach(execution.startLocalClient(socket, command, cwd, (code, output, clientError) -> {
                            commandSlot.close();
                            if (!stopped() && (clientError != null || code != 0)) MAIN.post(() -> presentationFailed(
                                    clientError != null ? clientError : new IOException("Wayland command exited (" + code + "): " + output)));
                        }));
                    } catch (RuntimeException startError) { commandSlot.close(); MAIN.post(() -> presentationFailed(startError)); }
                });
                return;
            }
            WaylandSession current = renderer;
            var transfer = resources.reserve();
            var connection = current.connect();
            transfer.attach(() -> connection.cancel(false));
            connection.whenComplete((fd, failure) -> MAIN.post(() -> {
                transfer.close();
                if (failure != null) { if (!stopped()) presentationFailed(failure); return; }
                if (stopped() || current != renderer) { try { fd.close(); } catch (IOException ignored) { } return; }
                var handoffSlot = resources.reserve();
                var commandSlot = resources.reserve();
                try {
                    WaylandClientLaunch handoff = new WaylandClientLaunch(context, fd, execution.commands.uid);
                    handoffSlot.attach(handoff::close);
                    handoff.transferred().whenComplete((value, deliveryError) -> {
                        handoffSlot.close();
                        if (deliveryError != null) {
                            commandSlot.close();
                            if (!stopped()) MAIN.post(() -> presentationFailed(deliveryError));
                        }
                    });
                    WORK.execute(() -> {
                        try {
                            if (stopped()) { commandSlot.close(); return; }
                            commandSlot.attach(execution.startClient(handoff, command, cwd, (code, output, clientError) -> {
                                commandSlot.close();
                                handoffSlot.close();
                                if (!stopped() && (clientError != null || code != 0)) MAIN.post(() -> presentationFailed(
                                        clientError != null ? clientError : new IOException("Wayland command exited (" + code + "): " + output)));
                            }));
                        } catch (RuntimeException startError) {
                            commandSlot.close(); handoffSlot.close(); MAIN.post(() -> presentationFailed(startError));
                        }
                    });
                } catch (IOException | RuntimeException startError) {
                    try { fd.close(); } catch (IOException ignored) { }
                    commandSlot.close(); handoffSlot.close(); presentationFailed(startError);
                }
            }));
        }

        @Override public void changed() {
            if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(this::changed); return; }
            presentation.retain(windows().stream().map(WaylandSession.Window::id).collect(java.util.stream.Collectors.toSet()));
            for (var listener : listeners) listener.changed();
            if (ready()) for (var window : windows()) if (window.mapped()) presentation.present(window.id());
        }
        @Override public void frame(long output, int width, int height) {
            for (var listener : listeners) listener.frame(output, width, height);
        }
        @Override public void failed(long output, String message) { fail(new IOException(message)); }
        @Override public void presentationChanged() { changed(); }
        @Override public void presentationFailed(Throwable failure) {
            if (stopped()) return;
            error = ShellAccess.usefulMessage(failure);
            DesktopAutomationEventJournal.record("wayland", "operation_failed", false, "session=" + id() + " detail=" + error);
            changed();
        }
        private void fail(Throwable failure) { MAIN.post(() -> end("FAILED", ShellAccess.usefulMessage(failure))); }
        void close() {
            if (Looper.myLooper() == Looper.getMainLooper()) end("CLOSED", "");
            else MAIN.post(() -> end("CLOSED", ""));
        }
        private void unregister() { if (registered) { registered = false; context.unregisterReceiver(admission); } }
        private void end(String next, String message) {
            if (stopped()) return;
            state = next; error = message;
            presentation.close();
            MAIN.removeCallbacks(timeout);
            unregister();
            if (renderer != null) renderer.close();
            resources.close();
            synchronized (SESSIONS) { SESSIONS.remove(id()); }
            DesktopAutomationEventJournal.record("wayland", "session_ended", message.isEmpty(), "session=" + id() + " " + message);
            changed();
        }
    }

    private WaylandSessions() { }
}
