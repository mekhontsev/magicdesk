package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import com.termux.x11.ICmdEntryInterface;
import com.termux.x11.X11Session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared X-server ownership. Android windows borrow outputs; Desktop owns neither. */
final class X11Sessions {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> new Thread(r, "X11Lifecycle"));
    private static final Map<String, Session> SESSIONS = new LinkedHashMap<>();
    private static final long START_TIMEOUT_MILLIS = 60_000;

    enum State { STARTING, READY, CLOSED, FAILED }
    interface Listener {
        void onChanged();
        default void onFrame(X11Session.Output output, int width, int height, boolean available) { }
    }

    static Session start(Context context, String name, String command) {
        Context app = context.getApplicationContext();
        TermuxIntegration.Endpoint endpoint = TermuxIntegration.inspect(app);
        endpoint.requireAvailable();
        if (name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("X11 session name must contain 1 to 128 characters");
        Session session = new Session(app, endpoint, name.trim(), command == null ? "" : command);
        synchronized (SESSIONS) { SESSIONS.put(session.id(), session); }
        try {
            MagicDeskRuntime.startTools(app, false);
            MAIN.postDelayed(session.timeout, START_TIMEOUT_MILLIS);
            WORK.execute(() -> {
                if (session.stopped()) return;
                try {
                    TermuxIntegration.runBackgroundShellCommand(app, endpoint, session.launch.serverCommand,
                            name, endpoint.homeDirectory, session.launch.stdin);
                } catch (RuntimeException error) { session.fail(error); }
            });
        } catch (RuntimeException error) { session.fail(error); }
        return session;
    }

    static Session find(String id) { synchronized (SESSIONS) { return SESSIONS.get(id); } }
    static List<Session> list() { synchronized (SESSIONS) { return new ArrayList<>(SESSIONS.values()); } }
    static int count() { synchronized (SESSIONS) { return SESSIONS.size(); } }
    static void closeAll() { for (Session session : list()) session.close(); }

    static void handoff(String method, String token, Bundle extras, int uid) throws RemoteException {
        if (extras == null || token == null) throw new SecurityException("Missing X11 owner handshake");
        Session session = find(extras.getString("session"));
        if (session == null || uid != session.endpoint.uid || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), session.launch.token.getBytes(StandardCharsets.UTF_8)))
            throw new SecurityException("X11 startup is no longer owned");
        IBinder binder = extras.getBinder("server");
        if (binder == null) throw new SecurityException("Missing X11 server");
        synchronized (session) {
            if (session.stopped()) throw new SecurityException("X11 session has ended");
            if ("attach".equals(method)) {
                if (session.server != null) throw new SecurityException("X11 server is already attached");
                binder.linkToDeath(session.death, 0);
                session.server = ICmdEntryInterface.Stub.asInterface(binder);
                session.server.retain(session.lifetime);
                return;
            }
            if (!"ready".equals(method) || session.server == null
                    || !binder.equals(session.server.asBinder())) throw new SecurityException("Unknown X11 server");
            if (!session.connecting && session.state == State.STARTING) {
                String display = extras.getString("display", "");
                session.launch.clientCommand(display, "true");
                session.connecting = true;
                session.display = display;
                WORK.execute(session::connect);
            }
        }
    }

    static final class Session {
        final String name;
        final TermuxIntegration.Endpoint endpoint;
        private final Context context;
        private final X11LaunchSpec launch;
        private final String startupCommand;
        private final IBinder lifetime = new Binder();
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();
        private final Runnable timeout = this::readinessExpired;
        private final IBinder.DeathRecipient death = () -> fail(new IllegalStateException("X11 server exited"));
        private ICmdEntryInterface server;
        private boolean connecting;
        private volatile X11Session renderer;
        private volatile State state = State.STARTING;
        private volatile String error = "";
        private volatile String display = "";

        Session(Context context, TermuxIntegration.Endpoint endpoint, String name, String command) {
            this.context = context;
            this.endpoint = endpoint;
            this.name = name;
            startupCommand = command;
            launch = new X11LaunchSpec(context.getApplicationInfo().sourceDir,
                    context.getApplicationInfo().nativeLibraryDir, context.getPackageName(), endpoint.homeDirectory);
        }

        String id() { return launch.id; }
        State state() { return state; }
        String error() { return error; }
        String display() { return display.isEmpty() ? "" : ":" + display; }
        boolean stopped() { return state == State.CLOSED || state == State.FAILED; }
        void listen(Listener listener) { listeners.add(listener); }
        void unlisten(Listener listener) { listeners.remove(listener); }

        X11Session.Output openOutput(long xid) {
            X11Session current = renderer;
            if (state != State.READY || current == null) throw new IllegalStateException("X11 session is not ready");
            return current.openOutput(xid);
        }

        void execute(String command) {
            if (state != State.READY) throw new IllegalStateException("X11 session is not ready");
            String script = launch.clientCommand(display, command);
            WORK.execute(() -> {
                if (state != State.READY) return;
                try {
                    TermuxIntegration.runBackgroundShellCommand(context, endpoint, script,
                            name, endpoint.homeDirectory, null);
                } catch (RuntimeException failure) {
                    error = ShellAccess.usefulMessage(failure);
                    changed();
                }
            });
        }

        private void connect() {
            X11Session pending = null;
            try {
                ICmdEntryInterface process;
                synchronized (this) { if (stopped()) return; process = server; }
                pending = new X11Session(MAIN::post, new X11Session.Listener() {
                    @Override public void onFrame(X11Session.Output output, int width, int height, boolean available) {
                        for (Listener listener : listeners) listener.onFrame(output, width, height, available);
                    }
                    @Override public void onDisconnected() { fail(new IllegalStateException("X11 renderer disconnected")); }
                });
                pending.connect(process.getXConnection());
                synchronized (this) {
                    if (stopped()) return;
                    renderer = pending;
                    pending = null;
                    state = State.READY;
                    MAIN.removeCallbacks(timeout);
                }
                changed();
                if (!startupCommand.isBlank()) execute(startupCommand);
            } catch (RemoteException | RuntimeException failure) { fail(failure); }
            finally { if (pending != null) pending.close(); }
        }

        private synchronized void readinessExpired() {
            if (state == State.STARTING) fail(new IllegalStateException("X11 server readiness timed out"));
        }

        void close() { finish(State.CLOSED, ""); }
        private void fail(Throwable failure) { finish(State.FAILED, ShellAccess.usefulMessage(failure)); }

        private void finish(State terminal, String message) {
            ICmdEntryInterface process;
            X11Session connection;
            synchronized (this) {
                if (stopped()) return;
                state = terminal;
                error = message;
                process = server;
                connection = renderer;
                renderer = null;
                MAIN.removeCallbacks(timeout);
            }
            synchronized (SESSIONS) { SESSIONS.remove(id(), this); }
            // Revoke admission first: a delayed RUN_COMMAND request must fail before creating X sockets.
            if (process != null) {
                try { process.stop(); } catch (RemoteException ignored) { }
                process.asBinder().unlinkToDeath(death, 0);
            }
            if (connection != null) WORK.execute(connection::close);
            changed();
            MagicDeskRuntime.refreshSettings();
        }

        private void changed() {
            MAIN.post(() -> { for (Listener listener : listeners) listener.onChanged(); });
        }
    }

    private X11Sessions() { }
}
