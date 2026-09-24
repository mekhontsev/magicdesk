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
    private static final X11LaunchTracker LAUNCHES = new X11LaunchTracker();
    private static final long START_TIMEOUT_MILLIS = 60_000;

    enum State { STARTING, READY, CLOSED, FAILED }
    record Host(int taskId, int displayId, long windowId, boolean focused, HostedSurfaceView.Geometry geometry) { }
    record Inspection(io.github.mekhontsev.magicdesk.x11.X11WindowInspection family, List<Host> hosts) { }
    record Application(Session session, long window) { }
    interface Listener {
        void onChanged();
        default Host inspectHost() { return null; }
        default void onFrame(X11Session.Output output, int width, int height, boolean available) { }
        default void onCursor(X11Session.Output output, X11Session.Cursor cursor) { }
        default void onWindowGesture(long window, io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture gesture) { }
        default void onDataOffer(X11DataExchange.Offer offer) { }
        default void onDragEvent(int operation, int output, boolean accepted) { }
    }

    static Session start(Context context, String name, String command, DesktopExecBackend backend, String keyboardDirectory) {
        String script = command == null || command.isBlank() ? "true" : command;
        String exec = "sh -c " + ShellCommandLine.quote(script).replace("%", "%%");
        var shortcut = new DesktopApplicationShortcut(name, "", exec, null, "", DesktopLaunchMode.AUTO,
                false, backend, false).withGraphics(new GraphicalLaunchOptions(true, keyboardDirectory));
        return start(context, name, command, "", false, "", RecentApplications.describe(context, shortcut, ""), backend, keyboardDirectory);
    }

    static Session startCommand(Context context, String name, String command, String directory, String desktopFile,
            boolean application, RecentApplicationStore.Entry recipe, DesktopExecBackend backend, String keyboardDirectory) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Missing X11 command");
        return start(context, name, command, DesktopExecWorkingDirectory.normalize(directory), application, desktopFile, recipe,
                backend, keyboardDirectory);
    }

    private static Session start(Context context, String name, String command, String directory, boolean application, String desktopFile,
            RecentApplicationStore.Entry recipe, DesktopExecBackend backend, String keyboardDirectory) {
        Context app = context.getApplicationContext();
        X11Execution execution = new X11Execution(app, backend, keyboardDirectory);
        if (name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("X11 session name must contain 1 to 128 characters");
        Session session = new Session(app, execution, name.trim(), command == null ? "" : command, directory, application,
                context.getResources().getConfiguration().densityDpi, desktopFile, recipe);
        synchronized (SESSIONS) { SESSIONS.put(session.id(), session); }
        try {
            MagicDeskRuntime.startTools(app, false);
            MAIN.postDelayed(session.timeout, START_TIMEOUT_MILLIS);
            WORK.execute(() -> {
                if (session.stopped()) return;
                var resource = session.resources.reserve();
                try {
                    resource.attach(execution.startServer(session.launch, (code, output, error) -> {
                        resource.close();
                        if (!session.stopped()) session.fail(error != null ? error
                                : new IllegalStateException("X11 server exited (" + code + "): " + output));
                    }));
                } catch (java.io.IOException | RuntimeException error) { resource.close(); session.fail(error); }
            });
        } catch (RuntimeException error) { session.fail(error); }
        return session;
    }

    static Session find(String id) { synchronized (SESSIONS) { return SESSIONS.get(id); } }
    static Application findRecipe(String key) {
        Application selected = null;
        for (Session session : list()) if (!session.stopped()) {
            Application match = session.findApplication(key);
            if (match != null) selected = match;
        }
        return selected;
    }

    private static void reconcileLaunches() {
        for (var match : LAUNCHES.takeMatches()) {
            Session origin = find(match.launch()), target = find(match.window().session());
            if (origin == null || target == null || origin.stopped() || target.stopped()) continue;
            long window = match.window().id();
            target.presentation.claim(window);
            synchronized (target) {
                if (origin.recipe != null) target.windowRecipes.put(window, origin.recipe);
            }
            if (origin.recentScope != null) target.recordUse(window, origin.recentScope);
            origin.redirect = new Application(target, window);
            DesktopAutomationEventJournal.record("x11", "launch_forwarded", true,
                    "session=" + origin.id() + " target=" + target.id() + " window=" + window);
            origin.close();
        }
    }

    static void forgetLaunchSource(String termuxPackage, String path) {
        for (Session session : list()) session.forgetRecipe(termuxPackage, path);
    }
    static List<Session> list() { synchronized (SESSIONS) { return new ArrayList<>(SESSIONS.values()); } }
    static int count() { synchronized (SESSIONS) { return SESSIONS.size(); } }
    static void closeAll() { for (Session session : list()) session.close(); }
    static void prepareForExit() { for (Session session : list()) session.presentation.close(); }

    static void handoff(String method, String token, Bundle extras, int uid) throws RemoteException {
        if (extras == null || token == null) throw new SecurityException("Missing X11 owner handshake");
        Session session = find(extras.getString("session"));
        if (session == null || uid != session.execution.serverUid || !MessageDigest.isEqual(
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

    static final class Session implements HostedWindowPresentation.Session {
        final String name;
        final X11Execution execution;
        final boolean application;
        final String presentationKey;
        private RecentApplicationStore.Entry recipe;
        private final Map<Long, RecentApplicationStore.Entry> windowRecipes = new LinkedHashMap<>();
        private volatile Application redirect;
        private RecentLaunchScope recentScope;
        private final Map<Integer, Long> hosts = new LinkedHashMap<>();
        private final X11Density density;
        private volatile int scalePercent;
        private final Context context;
        private final X11LaunchSpec launch;
        private final String startupCommand;
        private final String startupDirectory;
        private boolean hadWindows;
        private boolean hadApplicationWindow;
        private boolean startupFinished;
        private final Runnable windowTimeout = this::windowReadinessExpired;
        private final IBinder lifetime = new Binder();
        private final List<Listener> listeners = new CopyOnWriteArrayList<>();
        private final Runnable timeout = this::readinessExpired;
        private final IBinder.DeathRecipient death = () -> fail(new IllegalStateException("X11 server exited"));
        private IX11Server server;
        private boolean connecting;
        private final OperationResources resources = new OperationResources();
        private volatile X11Session renderer;
        private volatile State state = State.STARTING;
        private volatile String error = "";
        private volatile String display = "";
        private volatile List<X11Session.Window> windows = List.of();
        private Listener clipboardOwner;
        final HostedWindowPresentation presentation;
        private final HostedWindowOwners windowControlOwners = new HostedWindowOwners();

        Session(Context context, X11Execution execution, String name, String command,
                String directory, boolean application, int densityDpi, String desktopFile, RecentApplicationStore.Entry recipe) {
            this.context = context;
            this.execution = execution;
            this.name = name;
            this.application = application;
            this.recipe = recipe;
            presentation = new HostedWindowPresentation(context, this);
            presentationKey = X11PresentationPreferences.key(execution.commands.scope, desktopFile);
            scalePercent = X11PresentationPreferences.load(context, presentationKey);
            density = new X11Density(densityDpi);
            startupCommand = command;
            startupDirectory = directory;
            launch = execution.spec(density.resolve(scalePercent), application,
                    recipe == null || recipe.shortcut().graphics == null ? "" : recipe.shortcut().graphics.fileEnvironment());
        }

        int scalePercent() { return scalePercent; }
        String fileEnvironment() { return launch.fileEnvironment; }
        boolean canExecuteHostCommand() { return launch.fileEnvironment.isEmpty(); }
        synchronized void host(int taskId, long window, boolean focused) {
            if (focused) hosts.remove(taskId);
            hosts.put(taskId, window);
        }
        synchronized void releaseHost(int taskId) { hosts.remove(taskId); }
        synchronized int hostTaskId() { int id = -1; for (int task : hosts.keySet()) id = task; return id; }
        synchronized List<Integer> hostTaskIds() { return List.copyOf(hosts.keySet()); }
        private synchronized Application findApplication(String key) {
            Application result = null;
            for (var entry : windowRecipes.entrySet()) if (entry.getValue().key().equals(key))
                result = new Application(this, entry.getKey());
            if (result != null) return result;
            return recipe != null && recipe.key().equals(key) && (!application || !hadApplicationWindow)
                    ? new Application(this, 0) : null;
        }

        synchronized RecentApplicationStore.Entry windowRecipe(long window) {
            return window == 0 ? recipe : windowRecipes.get(window);
        }

        Application redirect() { return redirect; }

        private synchronized void associateRecipes(List<X11Session.Window> snapshot) {
            windowRecipes.keySet().retainAll(snapshot.stream().map(X11Session.Window::id).toList());
            if (recipe == null) return;
            String expected = recipe.shortcut().graphics == null ? "" : recipe.shortcut().graphics.startupClass();
            boolean initial = !hadApplicationWindow;
            for (var item : snapshot) if (!item.provisional()) {
                if (initial || item.matchesClass(expected)) windowRecipes.putIfAbsent(item.id(), recipe);
                initial = false;
            }
        }
        @Override public synchronized int hostTaskId(long window) {
            if (window == 0) return hostTaskId();
            int task = -1;
            for (var host : hosts.entrySet()) if (host.getValue() == window) task = host.getKey();
            return task;
        }

        synchronized boolean recordTaskUse(int taskId, RecentLaunchScope scope) {
            Long window = hosts.get(taskId);
            if (window == null) return false;
            recordUse(window, scope);
            return true;
        }

        private synchronized void forgetRecipe(String termuxPackage, String path) {
            // A live client survives shortcut deletion, but must not restore its deleted launch history.
            if (recipe != null && recipe.termuxPackage().equals(termuxPackage) && recipe.sourcePath().equals(path))
                recipe = null;
            windowRecipes.values().removeIf(entry -> entry.termuxPackage().equals(termuxPackage) && entry.sourcePath().equals(path));
        }

        synchronized void recordUse(long window, RecentLaunchScope scope) {
            if (state != State.READY) return;
            RecentApplicationStore.Entry entry = windowRecipes.get(window);
            if (entry == null) { recordUse(scope); return; }
            RecentApplications.record(context, entry.usedAt(System.currentTimeMillis()), scope);
        }

        synchronized void recordUse(RecentLaunchScope scope) {
            recentScope = scope;
            recordUse();
        }
        private synchronized void recordUse() {
            if (recipe != null && recentScope != null && state == State.READY && (!application || hadWindows))
                RecentApplications.record(context, recipe.usedAt(System.currentTimeMillis()), recentScope);
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
        java.util.concurrent.CompletableFuture<Inspection> inspectWindow(long window, int limit) {
            X11Session current = renderer;
            if (current == null || state != State.READY) throw new IllegalStateException("X11 session is not ready");
            var request = current.inspectWindow(window, limit);
            var result = new java.util.concurrent.CompletableFuture<Inspection>();
            request.whenComplete((family, failure) -> {
                if (failure != null) { result.completeExceptionally(failure); return; }
                MAIN.post(() -> {
                    if (result.isDone()) return;
                    if (renderer != current || state != State.READY) {
                        result.completeExceptionally(new IllegalStateException("X11 session changed during inspection"));
                        return;
                    }
                    try {
                        List<Host> hosts = new ArrayList<>();
                        for (Listener listener : listeners) {
                            Host host = listener.inspectHost();
                            if (host != null && (host.windowId() == window || host.windowId() == 0)) hosts.add(host);
                        }
                        result.complete(new Inspection(family, List.copyOf(hosts)));
                    } catch (RuntimeException error) { result.completeExceptionally(error); }
                });
            });
            result.whenComplete((value, error) -> { if (result.isCancelled()) request.cancel(false); });
            return result;
        }
        void claimWindow(long id) { presentation.claim(id); LAUNCHES.presented(id(), id); }
        @Override public boolean ready() { return state == State.READY; }
        @Override public boolean containsWindow(long window) { return windows.stream().anyMatch(item -> item.id() == window); }
        @Override public android.content.Intent windowIntent(Context context, long window) {
            return X11Activity.windowIntent(context, this, window);
        }
        @Override public io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout layout(long window) {
            return windows.stream().filter(item -> item.id() == window).map(X11Session.Window::layout)
                    .findFirst().orElse(io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout.NONE);
        }
        @Override public void presentationChanged() { changed(); }
        @Override public void presentationFailed(Throwable failure) {
            DesktopAutomationEventJournal.record("x11", "window_presentation_failed", false,
                    "session=" + id() + " detail=" + ShellAccess.usefulMessage(failure));
            error = ShellAccess.usefulMessage(failure);
            changed();
        }
        private String launchScope() {
            return execution.commands.scope + ":" + execution.commands.uid + ":" + execution.serverUid
                    + ":" + launch.fileEnvironment;
        }

        private void presentWindows() {
            if (!application || state != State.READY) return;
            for (var item : windows) if (item.mapped() && !item.provisional() && !LAUNCHES.reserved(id(), item.id()))
                if (presentation.present(item.id())) LAUNCHES.presented(id(), item.id());
        }
        boolean claimWindowControl(long id, Object host) {
            return windowControlOwners.claim(id, host);
        }
        void releaseWindowControl(Object host) {
            if (windowControlOwners.release(host)) changed();
        }
        void confirmFullscreen(long id, Object host,
                io.github.mekhontsev.magicdesk.x11.X11WindowManagement.Request request,
                io.github.mekhontsev.magicdesk.x11.X11WindowManagement.State actual) {
            X11Session current = renderer;
            if (windowControlOwners.owns(id, host) && current != null && state == State.READY)
                current.confirmWindowState(id, request, actual);
        }
        void confirmMaximized(long id, Object host, long serial, io.github.mekhontsev.magicdesk.hosted.HostedMaximization actual) {
            X11Session current = renderer;
            if (windowControlOwners.owns(id, host) && current != null && state == State.READY)
                current.confirmMaximized(id, serial, actual);
        }
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
        void closeWindow(long id, boolean force) {
            X11Session current = renderer;
            if (state != State.READY || current == null) throw new IllegalStateException("X11 session is not ready");
            current.closeWindow(id, force);
        }

        X11ShellBinding bindShell(ShellLayoutScope scope, int densityDpi, java.util.function.Consumer<String> ended) {
            X11Session current = renderer;
            if (!ready() || current == null || application) throw new IllegalStateException("Shell components require a ready retained X11 session");
            Object densityOwner = new Object();
            var binding = new X11ShellBinding(current, scope, reason -> {
                releaseDensity(densityOwner);
                ended.accept(reason);
            });
            hostDensity(densityOwner, densityDpi, true);
            return binding;
        }

        X11Session.Output openOutput(long xid) {
            X11Session current = renderer;
            if (state != State.READY || current == null) throw new IllegalStateException("X11 session is not ready");
            return current.openOutput(xid);
        }

        void execute(String command) {
            if (!canExecuteHostCommand()) throw new IllegalStateException(
                    "Launch guest commands through a Linux application recipe");
            execute(command, "");
        }

        private void execute(String command, String directory) {
            if (state != State.READY) throw new IllegalStateException("X11 session is not ready");
            String script = launch.clientCommand(display, command);
            WORK.execute(() -> {
                if (state != State.READY) return;
                var resource = resources.reserve();
                try {
                    resource.attach(execution.commands.start(script, directory, name, null, (code, output, failure) -> {
                        resource.close();
                        if (!stopped() && (failure != null || code != 0)) {
                            error = failure == null ? output : ShellAccess.usefulMessage(failure);
                            changed();
                        }
                    }));
                } catch (RuntimeException failure) {
                    resource.close();
                    error = ShellAccess.usefulMessage(failure);
                    changed();
                }
            });
        }

        private void executeStartup() {
            if (state != State.READY) return;
            var resource = resources.reserve();
            try {
                resource.attach(execution.commands.start(launch.clientCommand(display, startupCommand), startupDirectory, name, null,
                        (code, output, failure) -> {
                            resource.close();
                            if (stopped()) return;
                            if (failure != null) fail(failure);
                            else if (code != 0) fail(new IllegalStateException("X11 command exited (" + code + "): " + output));
                            else MAIN.post(() -> {
                                if (stopped()) return;
                                startupFinished = true;
                                LAUNCHES.completed(id());
                                reconcileLaunches();
                                for (Session session : list()) session.changed();
                                DesktopAutomationEventJournal.record("x11", "command_finished", true, "session=" + id() + " exit=0");
                                if (application && hadWindows && windows.isEmpty() && !stopped()) close();
                            });
                        }));
            } catch (RuntimeException failure) { resource.close(); throw failure; }
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
                    @Override public void onCursor(X11Session.Output output, X11Session.Cursor cursor) {
                        if (!stopped()) for (Listener listener : listeners) listener.onCursor(output, cursor);
                    }
                    @Override public void onWindowGesture(long window, io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture gesture) {
                        if (!stopped()) for (Listener listener : listeners) listener.onWindowGesture(window, gesture);
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
                        DesktopAutomationEventJournal.record("x11", "windows_changed", true,
                                "session=" + id() + " windows=" + snapshot.stream()
                                        .map(item -> item.id() + ":" + item.role() + ":" + item.mapped() + ":" + item.className()).toList());
                        windows = snapshot;
                        var live = snapshot.stream().map(X11Session.Window::id).collect(java.util.stream.Collectors.toSet());
                        presentation.retain(live);
                        associateRecipes(snapshot);
                        if (application) LAUNCHES.update(id(), launchScope(), snapshot);
                        windowControlOwners.retain(snapshot.stream().map(X11Session.Window::id).toList());
                        if (!snapshot.isEmpty()) {
                            boolean first = !hadWindows;
                            hadWindows = true;
                            hadApplicationWindow |= snapshot.stream().anyMatch(item -> !item.provisional());
                            if (first) recordUse();
                            if (hadApplicationWindow) MAIN.removeCallbacks(windowTimeout);
                        } else if (application && hadWindows && (hadApplicationWindow || startupFinished)) {
                            DesktopAutomationEventJournal.record("x11", "application_windows_gone", true, "session=" + id());
                            close(); return;
                        }
                        reconcileLaunches();
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
                if (!startupCommand.isBlank()) MAIN.post(() -> {
                    if (stopped()) return;
                    if (application) {
                        String expected = recipe == null || recipe.shortcut().graphics == null ? "" : recipe.shortcut().graphics.startupClass();
                        LAUNCHES.begin(id(), launchScope(), expected);
                        DesktopAutomationEventJournal.record("x11", "launch_tracking", true,
                                "session=" + id() + " scope=" + launchScope() + " class=" + expected);
                        MAIN.postDelayed(windowTimeout, START_TIMEOUT_MILLIS);
                    }
                    WORK.execute(() -> { try { executeStartup(); } catch (RuntimeException failure) { fail(failure); } });
                });
            } catch (RemoteException | RuntimeException failure) { fail(failure); }
            finally { if (pending != null) pending.close(); }
        }

        private synchronized void readinessExpired() {
            if (state == State.STARTING) fail(new IllegalStateException("X11 server readiness timed out"));
        }

        private void windowReadinessExpired() {
            if (application && windows.isEmpty() && !stopped())
                fail(new IllegalStateException(startupFinished
                        ? "The command finished without a window on this X server. It may have reused an application already running on another server."
                        : "X11 application did not create a window"));
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
            }
            synchronized (SESSIONS) { SESSIONS.remove(id(), this); }
            MAIN.post(() -> {
                LAUNCHES.remove(id());
                presentation.close();
                for (Session session : list()) session.changed();
            });
            DesktopAutomationEventJournal.record("x11", "session_finished", terminal != State.FAILED,
                    "session=" + id() + " state=" + terminal + " detail=" + message);
            // Revoke admission first: a delayed RUN_COMMAND request must fail before creating X sockets.
            if (process != null) process.asBinder().unlinkToDeath(death, 0);
            WORK.execute(() -> {
                resources.close();
                if (process != null) try { process.stop(); } catch (RemoteException ignored) { }
                if (connection != null) connection.close();
            });
            changed();
            MagicDeskRuntime.refreshSettings();
        }

        private void changed() {
            MAIN.post(() -> {
                for (Listener listener : listeners) listener.onChanged();
                presentWindows();
            });
        }
    }

    private X11Sessions() { }
}
