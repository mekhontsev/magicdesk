package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local semantic registry for visible interactive Console windows. */
final class ConsoleTerminalRegistry {
    private static final long MAIN_TIMEOUT_MILLIS = 2_000L;
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private ConsoleTerminalRegistry() {
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
            pruneLocked();
            if (ENTRIES.containsKey(id)) {
                throw new IllegalStateException("duplicate terminal id");
            }
            ENTRIES.put(id, new Entry(activity, session, view));
            ENTRIES.notifyAll();
        }
        DesktopAutomationEventJournal.record(
                "terminal", "opened", true,
                "terminalId=" + id + " task=" + activity.getTaskId());
        return id;
    }

    static String nextId() {
        return "terminal-" + Long.toString(
                NEXT_ID.incrementAndGet(), 36);
    }

    static void unregister(final String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        final Entry removed;
        synchronized (ENTRIES) {
            removed = ENTRIES.remove(id);
            pruneLocked();
            ENTRIES.notifyAll();
        }
        if (removed != null) {
            DesktopAutomationEventJournal.record(
                    "terminal", "closed", true, "terminalId=" + id);
        }
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
        synchronized (ENTRIES) {
            int count = 0;
            for (final Entry entry : ENTRIES.values()) {
                if (entry.activity.get() != null
                        && entry.session.get() != null
                        && entry.view.get() != null) {
                    count++;
                }
            }
            return count;
        }
    }

    static boolean awaitRegistration(
            final String id, final long timeoutMillis) {
        final long deadline = android.os.SystemClock.uptimeMillis()
                + Math.max(0L, timeoutMillis);
        synchronized (ENTRIES) {
            long remaining = timeoutMillis;
            while (!ENTRIES.containsKey(id) && remaining > 0L) {
                try {
                    ENTRIES.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remaining = deadline - android.os.SystemClock.uptimeMillis();
            }
            return ENTRIES.containsKey(id);
        }
    }

    static Snapshot status(final String id) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            return entry == null ? null : entry.snapshot(id);
        });
    }

    static String refreshWorkingDirectory(final String id) throws IOException {
        final ConsoleTerminalSession session;
        synchronized (ENTRIES) {
            final Entry entry = id == null ? null : ENTRIES.get(id);
            session = entry == null ? null : entry.session.get();
            if (entry != null && session == null) {
                ENTRIES.remove(id);
            }
        }
        if (session == null) {
            throw new IllegalArgumentException("terminal session not found");
        }
        return session.resolveWorkingDirectory();
    }

    static String read(final String id, final boolean transcript) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            if (entry == null) {
                return null;
            }
            final ConsoleTerminalSession session = entry.session.get();
            final ConsoleTerminalView view = entry.view.get();
            if (session == null || view == null) {
                return null;
            }
            return transcript ? session.transcript() : view.visibleText();
        });
    }

    static boolean write(final String id, final String text) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            final ConsoleTerminalSession session = entry == null
                    ? null : entry.session.get();
            if (session == null) {
                return false;
            }
            session.write(text);
            return true;
        });
    }

    static boolean sendKey(
            final String id, final int keyCode, final int metaState) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            final ConsoleTerminalView view = entry == null
                    ? null : entry.view.get();
            return view != null && view.sendKey(keyCode, metaState);
        });
    }

    static boolean close(final String id) {
        return callOnMain(() -> {
            final Entry entry = find(id);
            final Activity activity = entry == null
                    ? null : entry.activity.get();
            if (activity == null || activity.isDestroyed()) {
                return false;
            }
            activity.finishAndRemoveTask();
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
        ENTRIES.entrySet().removeIf(item -> !item.getValue().isAlive());
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
            MAIN.removeCallbacks(task);
            task.cancel(false);
            throw new IllegalStateException("terminal UI thread timed out", error);
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
                final String title) {
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
        }
    }

    private static final class Entry {
        final WeakReference<Activity> activity;
        final WeakReference<ConsoleTerminalSession> session;
        final WeakReference<ConsoleTerminalView> view;

        Entry(
                final Activity activity,
                final ConsoleTerminalSession session,
                final ConsoleTerminalView view) {
            this.activity = new WeakReference<>(activity);
            this.session = new WeakReference<>(session);
            this.view = new WeakReference<>(view);
        }

        boolean isAlive() {
            final Activity owner = activity.get();
            return owner != null
                    && !owner.isFinishing()
                    && !owner.isDestroyed()
                    && session.get() != null
                    && view.get() != null;
        }

        Snapshot snapshot(final String id) {
            final Activity owner = activity.get();
            final ConsoleTerminalSession terminal = session.get();
            if (owner == null || terminal == null || owner.isDestroyed()) {
                return null;
            }
            return new Snapshot(
                    id,
                    owner.getTaskId(),
                    owner.getDisplay() == null
                            ? 0 : owner.getDisplay().getDisplayId(),
                    owner.hasWindowFocus(),
                    terminal.isReady(),
                    terminal.processId(),
                    terminal.columns(),
                    terminal.rows(),
                    terminal.workingDirectory(),
                    terminal.title());
        }
    }
}
