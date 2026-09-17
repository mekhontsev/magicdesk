package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import io.github.mekhontsev.magicdesk.x11.IX11Server;
import io.github.mekhontsev.magicdesk.x11.X11Session;
import io.github.mekhontsev.magicdesk.x11.X11DataExchange;

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
        default void onDataOffer(X11DataExchange.Offer offer) { }
        default void onDragEvent(int operation, int output, boolean accepted) { }
    }

    static Session start(Context context, String name, String command) {
        String script = command == null || command.isBlank() ? "true" : command;
        String exec = "sh -c " + ShellCommandLine.quote(script).replace("%", "%%");
        var shortcut = new DesktopApplicationShortcut(name, "", exec, null, "", DesktopLaunchMode.AUTO,
                false, DesktopExecBackend.X11, false).withX11Desktop(true);
        return start(context, name, command, "", false, "", RecentApplications.describe(context, shortcut, ""));
    }

    static Session startCommand(Context context, String name, String command, String directory, String desktopFile,
            boolean application, RecentApplicationStore.Entry recipe) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Missing X11 command");
        return start(context, name, command, DesktopExecWorkingDirectory.normalize(directory), application, desktopFile, recipe);
    }

    private static Session start(Context context, String name, String command, String directory, boolean application, String desktopFile,
            RecentApplicationStore.Entry recipe) {
        Context app = context.getApplicationContext();
        TermuxIntegration.Endpoint endpoint = TermuxIntegration.inspect(app);
        endpoint.requireAvailable();
        if (name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("X11 session name must contain 1 to 128 characters");
        Session session = new Session(app, endpoint, name.trim(), command == null ? "" : command, directory, application,
                context.getResources().getConfiguration().densityDpi, desktopFile, recipe);
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
    static Session findRecipe(String key) {
        Session selected = null;
        for (Session session : list()) if (!session.stopped() && session.recipe != null && session.recipe.key().equals(key)) selected = session;
        return selected;
    }
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
                session.server = IX11Server.Stub.asInterface(binder);
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
        final boolean application;
        final String presentationKey;
        final RecentApplicationStore.Entry recipe;
        private final java.util.LinkedHashSet<Integer> hosts = new java.util.LinkedHashSet<>();
        private final X11Density density;
        private volatile int scalePercent;
        private final Context context;
        private final X11LaunchSpec launch;
        private final String startupCommand;
        private final String startupDirectory;
        private boolean hadWindows;
        private final Runnable windowTimeout = this::windowReadinessExpired;
        private final IBinder lifetime = new Binder();
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();
        private final Runnable timeout = this::readinessExpired;
        private final IBinder.DeathRecipient death = () -> fail(new IllegalStateException("X11 server exited"));
        private IX11Server server;
        private boolean connecting;
        private TermuxCommandResultReceiver.Registration startupResult;
        private volatile X11Session renderer;
        private volatile State state = State.STARTING;
        private volatile String error = "";
        private volatile String display = "";
        private volatile List<X11Session.Window> windows = List.of();
        private Listener clipboardOwner;
        private final java.util.Set<Long> presentedWindows = new java.util.HashSet<>();

        Session(Context context, TermuxIntegration.Endpoint endpoint, String name, String command,
                String directory, boolean application, int densityDpi, String desktopFile, RecentApplicationStore.Entry recipe) {
            this.context = context;
            this.endpoint = endpoint;
            this.name = name;
            this.application = application;
            this.recipe = recipe;
            presentationKey = X11PresentationPreferences.key(endpoint.packageName, desktopFile);
            scalePercent = X11PresentationPreferences.load(context, presentationKey);
            density = new X11Density(densityDpi);
            startupCommand = command;
            startupDirectory = directory;
            launch = new X11LaunchSpec(context.getApplicationInfo().sourceDir,
                    context.getApplicationInfo().nativeLibraryDir, context.getPackageName(), endpoint.packageName, endpoint.homeDirectory,
                    density.resolve(scalePercent), application);
        }

        int scalePercent() { return scalePercent; }
        synchronized void host(int taskId, boolean focused) {
            if (focused) hosts.remove(taskId);
            hosts.add(taskId);
        }
        synchronized void releaseHost(int taskId) { hosts.remove(taskId); }
        synchronized int hostTaskId() { int id = -1; for (int task : hosts) id = task; return id; }
        synchronized List<Integer> hostTaskIds() { return List.copyOf(hosts); }
        void recordUse() {
            if (recipe != null && state == State.READY && (!application || hadWindows))
                RecentApplications.record(context, recipe.usedAt(System.currentTimeMillis()));
        }
        synchronized int dpi() { return density.resolve(scalePercent); }
        synchronized void hostDensity(Object host, int dpi, boolean focused) {
            density.update(host, dpi, focused);
            publishDensity();
        }
        synchronized void releaseDensity(Object host) {
            density.release(host);
            publishDensity();
        }
        synchronized void setScale(int scale) {
            if (!AppPresentationProfile.isValidScale(scale)) throw new IllegalArgumentException("Invalid X11 scale");
            scalePercent = scale;
            publishDensity();
            changed();
        }
        private void publishDensity() {
            if (renderer != null && state == State.READY) renderer.setDpi(density.resolve(scalePercent));
        }

        String id() { return launch.id; }
        State state() { return state; }
        String error() { return error; }
        String display() { return display.isEmpty() ? "" : ":" + display; }
        boolean stopped() { return state == State.CLOSED || state == State.FAILED; }
        void listen(Listener listener) { listeners.add(listener); }
        void unlisten(Listener listener) { listeners.remove(listener); }
        List<X11Session.Window> windows() { return windows; }
        boolean claimWindow(long id) { return presentedWindows.add(id); }
        void releaseWindowClaim(long id) { presentedWindows.remove(id); }
        void claimClipboard(Listener owner) {
            clipboardOwner = owner;
            X11Session current = renderer;
            if (state == State.READY && current != null) current.dataExchange().clipboardActive(true);
        }
        void releaseClipboard(Listener owner) {
            if (clipboardOwner != owner) return;
            clipboardOwner = null;
            X11Session current = renderer;
            if (state == State.READY && current != null) current.dataExchange().clipboardActive(false);
        }
        X11DataExchange dataExchange() {
            X11Session current = renderer;
            if (state != State.READY || current == null) throw new IllegalStateException("X11 session is not ready");
            return current.dataExchange();
        }
        synchronized IX11Server contentFiles() {
            if (state != State.READY || server == null) throw new IllegalStateException("X11 session is not ready");
            return server;
        }
        void closeWindow(long id) {
            X11Session current = renderer;
            if (state == State.READY && current != null) current.closeWindow(id);
        }

        X11Session.Output openOutput(long xid) {
            X11Session current = renderer;
            if (state != State.READY || current == null) throw new IllegalStateException("X11 session is not ready");
            return current.openOutput(xid);
        }

        void execute(String command) {
            execute(command, "");
        }

        private void execute(String command, String directory) {
            if (state != State.READY) throw new IllegalStateException("X11 session is not ready");
            String script = launch.clientCommand(display, command);
            WORK.execute(() -> {
                if (state != State.READY) return;
                try {
                    TermuxIntegration.runBackgroundShellCommand(context, endpoint, script,
                            name, directory.isEmpty() ? endpoint.homeDirectory : directory, null);
                } catch (RuntimeException failure) {
                    error = ShellAccess.usefulMessage(failure);
                    changed();
                }
            });
        }

        private void executeStartup() {
            synchronized (this) {
                if (state != State.READY) return;
                startupResult = TermuxIntegration.runBackgroundShellCommandForResult(context, endpoint,
                        launch.clientCommand(display, startupCommand), name,
                        startupDirectory.isEmpty() ? endpoint.homeDirectory : startupDirectory, 0,
                        (result, failure) -> {
                            if (stopped()) return;
                            if (failure != null) fail(failure);
                            else if (result == null || !result.success()) fail(new IllegalStateException(
                                    result == null ? "X11 command returned no result" : result.usefulMessage()));
                        });
            }
        }

        private void connect() {
            X11Session pending = null;
            try {
                IX11Server process;
                synchronized (this) { if (stopped()) return; process = server; }
                pending = new X11Session(MAIN::post, new X11Session.Listener() {
                    @Override public void onFrame(X11Session.Output output, int width, int height, boolean available) {
                        for (Listener listener : listeners) listener.onFrame(output, width, height, available);
                    }
                    @Override public void onDisconnected() { fail(new IllegalStateException("X11 renderer disconnected")); }
                    @Override public void onDataOffer(X11DataExchange.Offer offer) {
                        if (stopped()) return;
                        if (offer.channel() == X11DataExchange.CLIPBOARD) {
                            if (clipboardOwner != null) clipboardOwner.onDataOffer(offer);
                        } else for (Listener listener : listeners) listener.onDataOffer(offer);
                    }
                    @Override public void onDragEvent(int operation, int output, boolean accepted) {
                        if (!stopped()) for (Listener listener : listeners) listener.onDragEvent(operation, output, accepted);
                    }
                    @Override public void onWindowsChanged(List<X11Session.Window> snapshot) {
                        if (stopped()) return;
                        windows = snapshot;
                        presentedWindows.retainAll(snapshot.stream().map(X11Session.Window::id).toList());
                        if (!snapshot.isEmpty()) {
                            boolean first = !hadWindows;
                            hadWindows = true;
                            if (first) recordUse();
                            MAIN.removeCallbacks(windowTimeout);
                        } else if (application && hadWindows) { close(); return; }
                        changed();
                    }
                });
                pending.connect(process.openConnection());
                synchronized (this) {
                    if (stopped()) return;
                    renderer = pending;
                    pending = null;
                    state = State.READY;
                    publishDensity();
                    MAIN.removeCallbacks(timeout);
                }
                changed();
                if (!application) recordUse();
                if (!startupCommand.isBlank()) {
                    if (application) MAIN.postDelayed(windowTimeout, START_TIMEOUT_MILLIS);
                    executeStartup();
                }
            } catch (RemoteException | RuntimeException failure) { fail(failure); }
            finally { if (pending != null) pending.close(); }
        }

        private synchronized void readinessExpired() {
            if (state == State.STARTING) fail(new IllegalStateException("X11 server readiness timed out"));
        }

        private void windowReadinessExpired() {
            if (application && !hadWindows && !stopped())
                fail(new IllegalStateException("X11 application did not create a window"));
        }

        void close() { finish(State.CLOSED, ""); }
        private void fail(Throwable failure) { finish(State.FAILED, ShellAccess.usefulMessage(failure)); }

        private void finish(State terminal, String message) {
            IX11Server process;
            X11Session connection;
            synchronized (this) {
                if (stopped()) return;
                state = terminal;
                error = message;
                process = server;
                connection = renderer;
                renderer = null;
                windows = List.of();
                MAIN.removeCallbacks(timeout);
                MAIN.removeCallbacks(windowTimeout);
                TermuxCommandResultReceiver.cancel(startupResult);
                startupResult = null;
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
