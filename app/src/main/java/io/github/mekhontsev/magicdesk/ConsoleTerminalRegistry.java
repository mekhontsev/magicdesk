package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Owns PTYs independently of their optional Activity and terminal View. */
final class ConsoleTerminalRegistry {
    interface ProcessRefreshListener {
        void onComplete(boolean changed);
    }

    private static final long MAIN_TIMEOUT_MILLIS = 2_000L;
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private ConsoleTerminalRegistry() {
    }

    static ConsoleTerminalSession acquire(final String id,
            final Function<ConsoleTerminalSession.Listener, ConsoleTerminalSession> factory) {
        if (!validId(id)) { throw new IllegalArgumentException("invalid terminal id"); }
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(id);
            if (entry == null) {
                if (factory == null) { throw new IllegalStateException("terminal session no longer exists"); }
                if (ENTRIES.size() >= 32) {
                    throw new IllegalStateException("too many terminal sessions; close an existing session");
                }
                entry = new Entry(id, factory);
                ENTRIES.put(id, entry);
            }
            return entry.session;
        }
    }

    static String register(
            final Activity activity,
            final ConsoleTerminalSession session,
            final ConsoleTerminalView view,
            final String requestedId) {
        if (activity == null || session == null || view == null) {
            throw new IllegalArgumentException("incomplete terminal window");
        }
        final String id = validId(requestedId)
                ? requestedId : nextId();
        synchronized (ENTRIES) {
            final Entry entry = ENTRIES.get(id);
            if (entry == null || entry.session != session) {
                throw new IllegalStateException("terminal session is not owned by the runtime");
            }
            final Activity previous = entry.activity.get();
            final ConsoleTerminalView previousView = entry.view.get();
            if (previousView != null && previousView != view) { previousView.attach(null, null); }
            entry.activity = new WeakReference<>(activity);
            entry.view = new WeakReference<>(view);
            entry.attachmentGeneration++;
            if (previous != null && previous != activity && !previous.isDestroyed()) {
                previous.finishAndRemoveTask();
            }
            ENTRIES.notifyAll();
        }
        DesktopAutomationEventJournal.record(
                "terminal", "opened", true,
                "terminalId=" + id + " task=" + activity.getTaskId());
        return id;
    }

    static String nextId() {
        synchronized (ENTRIES) {
            String id;
            do { id = "terminal-" + Long.toString(NEXT_ID.incrementAndGet(), 36); }
            while (ENTRIES.containsKey(id));
            return id;
        }
    }

    static void detach(final String id, final Activity activity) {
        synchronized (ENTRIES) {
            final Entry entry = ENTRIES.get(id);
            if (entry == null || entry.activity.get() != activity) { return; }
            final ConsoleTerminalView view = entry.view.get();
            // Release input and resize ownership before Android finishes the window.
            if (view != null) { view.attach(null, null); }
            entry.activity.clear();
            entry.view.clear();
            ENTRIES.notifyAll();
        }
        DesktopAutomationEventJournal.record("terminal", "detached", true, "terminalId=" + id);
        MagicDeskRuntime.refreshNotification();
    }

    static List<Snapshot> list() {
        return callOnMain(() -> {
            final List<Snapshot> snapshots = new ArrayList<>();
            synchronized (ENTRIES) {
                pruneLocked();
                for (final Map.Entry<String, Entry> item
                        : ENTRIES.entrySet()) {
                    final Snapshot snapshot = item.getValue().snapshot(
                            item.getKey());
                    if (snapshot != null) {
                        snapshots.add(snapshot);
                    }
                }
            }
            return snapshots;
        });
    }

    static int registeredCount() {
        synchronized (ENTRIES) { return ENTRIES.size(); }
    }

    static int windowCount() {
        synchronized (ENTRIES) {
            int count = 0;
            for (final Entry entry : ENTRIES.values()) {
                if (entry.activity.get() != null
                        && entry.view.get() != null) {
                    count++;
                }
            }
            return count;
        }
    }

    static boolean awaitRegistration(
            final String id, final long timeoutMillis) {
        return awaitAttachment(id, 0L, timeoutMillis);
    }

    static long attachmentGeneration(final String id) {
        synchronized (ENTRIES) {
            final Entry entry = ENTRIES.get(id);
            return entry == null ? 0L : entry.attachmentGeneration;
        }
    }

    static boolean awaitAttachment(
            final String id, final long previousGeneration, final long timeoutMillis) {
        final long deadline = android.os.SystemClock.uptimeMillis()
                + Math.max(0L, timeoutMillis);
        synchronized (ENTRIES) {
            long remaining = timeoutMillis;
            while (!hasWindowLocked(id, previousGeneration) && remaining > 0L) {
                try {
                    EventDrivenWaits.await(
                            ENTRIES,
                            EventDrivenWaits.Reason.TERMINAL_REGISTRATION,
                            remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remaining = deadline - android.os.SystemClock.uptimeMillis();
            }
            return hasWindowLocked(id, previousGeneration);
        }
    }

    private static boolean hasWindowLocked(final String id, final long previousGeneration) {
        final Entry entry = ENTRIES.get(id);
        return entry != null && entry.attachmentGeneration > previousGeneration
                && entry.activity.get() != null && entry.view.get() != null;
    }

    static Snapshot status(final String id) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            return entry == null ? null : entry.snapshot(id);
        });
    }

    static Snapshot snapshotForTask(final int taskId) {
        return callOnMain(() -> {
            synchronized (ENTRIES) {
                pruneLocked();
                for (final Map.Entry<String, Entry> item
                        : ENTRIES.entrySet()) {
                    final Snapshot snapshot = item.getValue().snapshot(
                            item.getKey());
                    if (snapshot != null && snapshot.taskId == taskId) {
                        return snapshot;
                    }
                }
            }
            return null;
        });
    }

    static void refreshForegroundProcesses(
            final List<Integer> taskIds,
            final ProcessRefreshListener listener) {
        final List<ConsoleTerminalSession> sessions = callOnMain(() -> {
            final Set<Integer> requested = taskIds == null
                    ? java.util.Collections.emptySet()
                    : new HashSet<>(taskIds);
            final List<ConsoleTerminalSession> result = new ArrayList<>();
            synchronized (ENTRIES) {
                pruneLocked();
                for (final Entry entry : ENTRIES.values()) {
                    final Activity activity = entry.activity.get();
                    final ConsoleTerminalSession session = entry.session;
                    if (activity != null
                            && session != null
                            && requested.contains(
                                    Integer.valueOf(activity.getTaskId()))) {
                        result.add(session);
                    }
                }
            }
            return result;
        });
        if (sessions.isEmpty()) {
            if (listener != null) {
                MAIN.post(() -> listener.onComplete(false));
            }
            return;
        }
        final AtomicInteger remaining = new AtomicInteger(sessions.size());
        final AtomicBoolean changed = new AtomicBoolean();
        for (final ConsoleTerminalSession session : sessions) {
            session.requestForegroundProcess((process, processChanged, error) -> {
                if (processChanged) {
                    changed.set(true);
                }
                if (remaining.decrementAndGet() == 0 && listener != null) {
                    listener.onComplete(changed.get());
                }
            });
        }
    }

    static String refreshWorkingDirectory(final String id) throws IOException {
        final ConsoleTerminalSession session;

        synchronized (ENTRIES) {
            final Entry entry = id == null ? null : ENTRIES.get(id);
            session = entry == null ? null : entry.session;
            if (entry != null && session == null) {
                ENTRIES.remove(id);
            }
        }
        if (session == null) {
            throw new IllegalArgumentException("terminal session not found");
        }
        return session.resolveWorkingDirectory();
    }

    static TerminalProcessInfo refreshForegroundProcess(final String id)
            throws IOException {
        final ConsoleTerminalSession session;
        synchronized (ENTRIES) {
            final Entry entry = id == null ? null : ENTRIES.get(id);
            session = entry == null ? null : entry.session;
            if (entry != null && session == null) {
                ENTRIES.remove(id);
            }
        }
        if (session == null) {
            throw new IllegalArgumentException("terminal session not found");
        }
        return session.resolveForegroundProcess();
    }

    static String read(final String id, final boolean transcript) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            if (entry == null) {
                return null;
            }
            final ConsoleTerminalSession session = entry.session;
            final ConsoleTerminalView view = entry.view.get();
            return transcript ? session.transcript() : view != null ? view.visibleText()
                    : session.emulator().getSelectedText(0, 0, session.columns() - 1, session.rows() - 1);
        });
    }

    static boolean write(final String id, final String text) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            final ConsoleTerminalSession session = entry == null
                    ? null : entry.session;
            if (session == null) {
                return false;
            }
            session.write(text);
            return true;
        });
    }

    static ConsoleTerminalSession.Metadata metadata(final String id) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            return entry == null ? null : entry.session.metadata();
        });
    }

    static String commandOutput(final String id, final long commandId) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            return entry == null ? null : entry.session.emulator().getCommandHistory().output(commandId);
        });
    }

    static List<com.termux.terminal.TerminalCommandHistory.Snapshot> commands(final String id) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            return entry == null ? List.of() : entry.session.emulator().getCommandHistory().snapshots();
        });
    }

    record LinkSpan(int row, int startColumn, int endColumn, String uri, String id) { }

    static List<LinkSpan> links(final String id) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            final List<LinkSpan> links = new ArrayList<>();
            if (entry == null) { return links; }
            final var emulator = entry.session.emulator();
            final var screen = emulator.getScreen();
            for (int row = 0; row < emulator.mRows; row++) {
                for (int column = 0; column < emulator.mColumns;) {
                    final var link = screen.getHyperlink(column, row);
                    if (link == null) { column++; continue; }
                    final int start = column++;
                    while (column < emulator.mColumns && link.equals(screen.getHyperlink(column, row))) { column++; }
                    links.add(new LinkSpan(row, start, column, link.uri(), link.id()));
                    if (links.size() == 256) { return links; }
                }
            }
            return links;
        });
    }

    static boolean sendKey(
            final String id, final int keyCode, final int metaState) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            final ConsoleTerminalView view = entry == null
                    ? null : entry.view.get();
            return view != null ? view.sendKey(keyCode, metaState)
                    : entry != null && entry.session.sendKey(keyCode, metaState);
        });
    }

    static boolean close(final String id) {
        return callOnMain(() -> {
            final Entry entry;
            synchronized (ENTRIES) {
                entry = ENTRIES.remove(id);
                ENTRIES.notifyAll();
            }
            if (entry == null) { return false; }
            final Activity activity = entry.activity.get();
            final ConsoleTerminalView view = entry.view.get();
            if (view != null) { view.attach(null, null); }
            entry.session.close();
            TerminalNotifications.cancel(id);
            if (activity != null && !activity.isDestroyed()) {
                activity.finishAndRemoveTask();
            }
            DesktopAutomationEventJournal.record("terminal", "closed", true, "terminalId=" + id);
            MagicDeskRuntime.refreshNotification();
            return true;
        });
    }

    static void closeAll() {
        callOnMain(() -> {
            final List<String> ids;
            synchronized (ENTRIES) { ids = new ArrayList<>(ENTRIES.keySet()); }
            for (final String id : ids) { close(id); }
            return null;
        });
    }

    static boolean hide(final String id) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            if (entry == null) { return false; }
            final Activity activity = entry.activity.get();
            if (activity == null) { return true; }
            detach(id, activity);
            if (!activity.isDestroyed()) { activity.finishAndRemoveTask(); }
            return true;
        });
    }

    private static Entry find(final String id) {
        synchronized (ENTRIES) {
            return findLocked(id);
        }
    }

    private static Entry findLocked(final String id) {
        pruneLocked();
        return id == null ? null : ENTRIES.get(id);
    }

    private static void pruneLocked() {
        for (final Entry entry : ENTRIES.values()) {
            final Activity activity = entry.activity.get();
            if (activity != null && activity.isDestroyed()) {
                entry.activity.clear();
                entry.view.clear();
            }
        }
    }

    private static <T> T callOnMain(final Callable<T> action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return action.call();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
        final FutureTask<T> task = new FutureTask<>(action);
        MAIN.post(task);
        try {
            return task.get(MAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("terminal operation interrupted", error);
        } catch (ExecutionException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("terminal operation failed", cause);
        } catch (TimeoutException error) {
            throw new IllegalStateException("terminal UI thread timed out", error);
        } finally {
            // A cancelled MCP worker must not leave queued terminal input behind.
            task.cancel(false);
            MAIN.removeCallbacks(task);
        }
    }

    private static boolean validId(final String id) {
        return id != null && id.matches("terminal-[0-9a-z]+");
    }

    static final class Snapshot {
        final String id;
        final int taskId;
        final int displayId;
        final boolean focused;
        final boolean ready;
        final long processId;
        final int columns;
        final int rows;
        final String workingDirectory;
        final String title;
        final String backend;
        final TerminalProcessInfo foregroundProcess;

        Snapshot(
                final String id,
                final int taskId,
                final int displayId,
                final boolean focused,
                final boolean ready,
                final long processId,
                final int columns,
                final int rows,
                final String workingDirectory,
                final String title,
                final String backend,
                final TerminalProcessInfo foregroundProcess) {
            this.id = id;
            this.taskId = taskId;
            this.displayId = displayId;
            this.focused = focused;
            this.ready = ready;
            this.processId = processId;
            this.columns = columns;
            this.rows = rows;
            this.workingDirectory = workingDirectory;
            this.title = title;
            this.backend = backend;
            this.foregroundProcess = foregroundProcess == null
                    ? TerminalProcessInfo.unknown() : foregroundProcess;
        }

        String taskLabel(final String fallback) {
            return TerminalTaskLabel.resolve(
                    fallback, foregroundProcess, title);
        }
    }

    private static final class Entry implements ConsoleTerminalSession.Listener {
        final String id;
        WeakReference<Activity> activity = new WeakReference<>(null);
        WeakReference<ConsoleTerminalView> view = new WeakReference<>(null);
        final ConsoleTerminalSession session;
        long attachmentGeneration;
        final TerminalNotificationLimiter notifications = new TerminalNotificationLimiter();

        Entry(final String id,
                final Function<ConsoleTerminalSession.Listener, ConsoleTerminalSession> factory) {
            this.id = id;
            session = factory.apply(this);
        }

        private void notifyView(final java.util.function.Consumer<ConsoleTerminalSession.Listener> action) {
            final Activity owner = activity.get();
            if (owner instanceof ConsoleTerminalSession.Listener listener
                    && !owner.isDestroyed() && !owner.isFinishing()) { action.accept(listener); }
        }

        @Override public void onScreenChanged() { notifyView(ConsoleTerminalSession.Listener::onScreenChanged); }
        @Override public void onReady() { notifyView(ConsoleTerminalSession.Listener::onReady); }
        @Override public void onFinished() { ConsoleTerminalRegistry.close(id); }
        @Override public void onError(final IOException error) {
            session.appendLocalMessage(ShellAccess.usefulMessage(error));
            notifyView(listener -> listener.onError(error));
        }
        @Override public void onTitleChanged(final String title) { notifyView(listener -> listener.onTitleChanged(title)); }
        @Override public void onCopyRequested(final String text) { notifyView(listener -> listener.onCopyRequested(text)); }
        @Override public void onPasteRequested() { notifyView(ConsoleTerminalSession.Listener::onPasteRequested); }
        @Override public void onBell() { notifyView(ConsoleTerminalSession.Listener::onBell); }
        @Override public void onMetadataChanged() { notifyView(ConsoleTerminalSession.Listener::onMetadataChanged); }
        @Override public void onNotification(final String message) {
            if (notifications.accept(android.os.SystemClock.elapsedRealtime())) {
                TerminalNotifications.show(snapshot(id), message);
            }
            notifyView(listener -> listener.onNotification(message));
        }

        Snapshot snapshot(final String id) {
            final Activity owner = activity.get();
            final ConsoleTerminalSession terminal = session;
            final boolean attached = owner != null && !owner.isDestroyed();
            return new Snapshot(
                    id,
                    attached ? owner.getTaskId() : -1,
                    !attached ? -1 : owner.getDisplay() == null
                            ? 0 : owner.getDisplay().getDisplayId(),
                    attached && owner.hasWindowFocus(),
                    terminal.isReady(),
                    terminal.processId(),
                    terminal.columns(),
                    terminal.rows(),
                    terminal.workingDirectory(),
                    terminal.title(),
                    terminal.backend().wireName,
                    terminal.foregroundProcess());
        }
    }
}
