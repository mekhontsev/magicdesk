package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Read-only setup observation shared by the panel and automation. */
final class DesktopSetupStatus {
    enum State { CHECKING, READY, SETUP_REQUIRED, RESTART_REQUIRED, UNKNOWN }

    record Snapshot(State state, DeviceSetupManager.Audit audit, String error) { }

    private static final Set<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile Snapshot sSnapshot = new Snapshot(State.CHECKING, null, "");
    private static boolean sInitialized;
    private static boolean sReading;
    private static boolean sRefreshPending;
    private static long sAccessGeneration;

    private DesktopSetupStatus() { }

    static void initialize(Context context) {
        if (!RuntimeCapabilities.allowsDesktop(android.os.Build.VERSION.SDK_INT)) return;
        synchronized (DesktopSetupStatus.class) {
            if (sInitialized) return;
            sInitialized = true;
        }
        final Context app = context.getApplicationContext();
        ShellAccess.addStateListener(access -> {
            synchronized (DesktopSetupStatus.class) {
                ++sAccessGeneration;
                sSnapshot = new Snapshot(State.CHECKING, null, "");
            }
            notifyListeners();
            if (access.isReady()) refresh(app);
        });
    }

    static Snapshot current() { return sSnapshot; }

    static void addListener(Runnable listener) {
        LISTENERS.add(listener);
        listener.run();
    }

    static void removeListener(Runnable listener) { LISTENERS.remove(listener); }

    static void refresh(Context context) {
        if (!RuntimeCapabilities.allowsDesktop(android.os.Build.VERSION.SDK_INT) || !ShellAccess.isReady()) return;
        final Context app = context.getApplicationContext();
        final long generation;
        synchronized (DesktopSetupStatus.class) {
            if (sReading) {
                sRefreshPending = true;
                return;
            }
            sReading = true;
            generation = sAccessGeneration;
        }
        new Thread(() -> {
            Snapshot result;
            try {
                final DeviceSetupManager.Audit audit = DeviceSetupManager.audit(app);
                result = new Snapshot(evaluate(audit.shellReady, audit.configurationReady,
                        audit.rebootRequired), audit, audit.runtimeError);
            } catch (RuntimeException error) {
                result = new Snapshot(State.UNKNOWN, null, ShellAccess.usefulMessage(error));
            }
            final boolean changedAccess;
            final boolean refreshPending;
            synchronized (DesktopSetupStatus.class) {
                sReading = false;
                changedAccess = generation != sAccessGeneration;
                refreshPending = sRefreshPending;
                sRefreshPending = false;
                if (!changedAccess && ShellAccess.isReady()) sSnapshot = result;
            }
            if (!changedAccess) notifyListeners();
            if (changedAccess || refreshPending) refresh(app);
        }, "MagicDeskSetupStatus").start();
    }

    static State evaluate(boolean inspected, boolean configured, boolean rebootRequired) {
        if (!inspected) return State.UNKNOWN;
        if (!configured) return State.SETUP_REQUIRED;
        return rebootRequired ? State.RESTART_REQUIRED : State.READY;
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) listener.run();
    }
}
