package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns the fail-open physical display override while the shell stream is alive. */
public final class PhoneDisplayGuard {
    private static final String TAG = "MagicDeskPhoneDisplay";
    private static final String GUARD_COMMAND =
            "io.github.mekhontsev.magicdesk.PhoneDisplayGuardCommand";
    private static final String DISPLAY_COMMAND =
            "/system/bin/cmd display ";
    private static final long START_TIMEOUT_MILLIS = 6_000L;
    private static final long STOP_TIMEOUT_MILLIS = 3_000L;
    private static final Object LOCK = new Object();
    private static final Object OPERATION_LOCK = new Object();
    private static Session sSession;
    private static int sGeneration;
    private static volatile String sRestoreOperation;
    private static volatile boolean sScreenOff;
    private static volatile String sLastProtectedUidSummary = "none";
    private static final java.util.concurrent.ExecutorService OPERATIONS =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "MagicDeskPhonePower");
                thread.setDaemon(true);
                return thread;
            });
    private static boolean sProbeRequested;

    private PhoneDisplayGuard() {
    }

    public static boolean isAvailable() {
        synchronized (LOCK) {
            if (!ShellAccess.isReady()) {
                sRestoreOperation = null;
                sProbeRequested = false;
                return false;
            }
            if (!sProbeRequested) {
                sProbeRequested = true;
                checkAvailable(ignored -> MagicDeskRuntime.refreshPlatformState());
            }
        }
        return sRestoreOperation != null;
    }

    static void checkAvailable(java.util.function.Consumer<Boolean> result) {
        OPERATIONS.execute(() -> result.accept(ShellAccess.isReady() && resolveRestoreOperation() != null));
    }

    static void setScreenOff(boolean off, java.util.function.Consumer<Boolean> result) {
        setScreenOff(off, android.view.Display.INVALID_DISPLAY, result);
    }

    static void setScreenOff(boolean off, int displayId, java.util.function.Consumer<Boolean> result) {
        OPERATIONS.execute(() -> {
            boolean success = false;
            try {
                success = off ? enable(displayId) : disable();
            } catch (RuntimeException error) {
                CompatibilityDiagnostics.record("PHONE-SCREEN-002", "Could not change the phone screen state",
                        "screenOff=" + off, error);
            }
            if (result != null) result.accept(success);
        });
    }

    public static boolean enable(final int desktopDisplayId) {
        synchronized (OPERATION_LOCK) { return enableLocked(desktopDisplayId); }
    }

    private static boolean enableLocked(final int desktopDisplayId) {
        final String restoreOperation = resolveRestoreOperation();
        if (restoreOperation == null) {
            Log.w(TAG, "DisplayManager has no supported display restore command");
            return false;
        }
        final Session session;
        final boolean startSession;
        synchronized (LOCK) {
            if (sSession == null) {
                session = new Session(
                        ++sGeneration,
                        restoreOperation,
                        desktopDisplayId);
                sSession = session;
                startSession = true;
            } else {
                session = sSession;
                startSession = false;
            }
        }
        if (startSession) {
            session.start();
        }
        if (session.awaitReady()) {
            return true;
        }
        session.requestRestore();
        if (!session.awaitStopped()) session.closeStream();
        resetWithoutSession();
        return false;
    }

    public static boolean disable() {
        synchronized (OPERATION_LOCK) { return disableLocked(); }
    }

    private static boolean disableLocked() {
        final Session session;
        synchronized (LOCK) {
            session = sSession;
        }
        if (session != null) {
            session.requestRestore();
            if (!session.awaitStopped()) {
                session.closeStream();
            }
        } else if (!sScreenOff) return true;

        // This idempotent reset confirms recovery even when the guarded
        // process disappeared before it could acknowledge the restore.
        final boolean reset = resetWithoutSession();
        if (reset) publishState(false);
        return reset;
    }

    public static void requestRestore() {
        final Session session;
        synchronized (LOCK) {
            session = sSession;
        }
        if (session != null) {
            session.requestRestore();
        }
    }

    public static boolean isActive() {
        synchronized (LOCK) {
            return sSession != null || sScreenOff;
        }
    }

    public static boolean isScreenOff() {
        return sScreenOff;
    }

    public static boolean restoreForDisplay(int displayId) {
        synchronized (OPERATION_LOCK) {
            synchronized (LOCK) {
                if (sSession == null || sSession.mDesktopDisplayId != displayId) return true;
            }
            return disableLocked();
        }
    }

    static String protectedUidSummary() {
        final Session session;
        synchronized (LOCK) {
            session = sSession;
        }
        return session == null ? "inactive, last=" + sLastProtectedUidSummary
                : session.protectionSummary();
    }

    private static void onReady(final Session session) {
        final boolean current;
        synchronized (LOCK) {
            current = sSession == session;
        }
        if (!current) {
            session.requestRestore();
            return;
        }
        publishState(true);
    }

    private static void onStopped(
            final Session session,
            final boolean expected,
            final boolean acknowledgedRestore,
            final String failure) {
        final boolean restored;
        synchronized (LOCK) {
            if (sSession != session) return;
            // Reset before admitting a replacement owner; an old helper must not turn its panel on.
            restored = acknowledgedRestore || resetWithoutSession();
            sSession = null;
            if (restored) publishState(false);
        }
        if (!expected || !restored) {
            Log.w(TAG, "Phone display guard stopped: " + failure);
            CompatibilityDiagnostics.record(
                    "PHONE-SCREEN-003",
                    "The phone-screen guard stopped",
                    failure);
        }
    }

    private static boolean resetWithoutSession() {
        final String restoreOperation = resolveRestoreOperation();
        if (restoreOperation == null) {
            return false;
        }
        try {
            return ShellAccess.executeCommand(DISPLAY_COMMAND + restoreOperation + " 0").exitCode == 0;
        } catch (IOException error) {
            Log.w(TAG, "Cannot reset phone display", error);
            return false;
        }
    }

    private static String resolveRestoreOperation() {
        final String cached = sRestoreOperation;
        if (cached != null) {
            return cached;
        }
        try {
            final String operation = DisplayPowerCommands.resolveSupportedRestoreOperation(
                    command -> ShellAccess.executeCommand(
                            DISPLAY_COMMAND + command).output);
            if (operation != null) {
                sRestoreOperation = operation;
            }
            return operation;
        } catch (IOException error) {
            Log.w(TAG, "Cannot inspect display power commands", error);
            return null;
        }
    }

    private static void publishState(final boolean screenOff) {
        if (sScreenOff == screenOff) {
            return;
        }
        sScreenOff = screenOff;
        MagicDeskRuntime.refreshPlatformState();
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // A closed stream can no longer own the display override.
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static final class Session implements Runnable {
        private final int mGeneration;
        private final String mRestoreOperation;
        private final int mDesktopDisplayId;
        private final CountDownLatch mReady = new CountDownLatch(1);
        private final CountDownLatch mStopped = new CountDownLatch(1);
        private volatile boolean mRestoreRequested;
        private volatile boolean mGuardReady;
        private volatile String mFailure = "guard exited before ready";
        private volatile String mProtectedUidSummary = "pending";
        private volatile ShellStreamHandle mStream;
        private volatile IBackgroundWorkLease mWork;
        private volatile IBackgroundWorkLease mDisplayWork;
        private final android.content.BroadcastReceiver mScreenReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
                // Release on wake, not on inactivity sleep: CPU work must continue while the panel is off.
                requestRestore();
            }
        };

        Session(
                final int generation,
                final String restoreOperation,
                final int desktopDisplayId) {
            mGeneration = generation;
            mRestoreOperation = restoreOperation;
            mDesktopDisplayId = desktopDisplayId;
        }

        void start() {
            final Thread thread = new Thread(
                    this,
                    "MagicDeskPhoneDisplay-" + mGeneration);
            thread.setDaemon(true);
            thread.start();
        }

        boolean isReady() {
            return mGuardReady
                    && !mRestoreRequested
                    && mStopped.getCount() != 0;
        }

        boolean awaitReady() {
            try {
                // EVENT_WAIT: helper acknowledgement; timeout requests restoration.
                return mReady.await(
                        START_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS) && isReady();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        boolean awaitStopped() {
            try {
                // EVENT_WAIT: helper cleanup; timeout closes its owning stream.
                return mStopped.await(
                        STOP_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void requestRestore() {
            mRestoreRequested = true;
            final ShellStreamHandle stream = mStream;
            if (stream == null) {
                return;
            }
            try {
                stream.writeLine(PhoneDisplayGuardCommand.RESTORE);
            } catch (IOException error) {
                mFailure = "restore request failed: "
                        + usefulMessage(error);
                closeQuietly(stream);
            }
        }

        void closeStream() {
            closeQuietly(mStream);
        }

        String protectionSummary() {
            final IBackgroundWorkLease work = mWork;
            if (work != null) {
                try {
                    mProtectedUidSummary = work.state();
                    if (mDisplayWork != null) mProtectedUidSummary += "; external=" + mDisplayWork.state();
                    sLastProtectedUidSummary = mProtectedUidSummary;
                } catch (android.os.RemoteException | RuntimeException error) {
                    return "unavailable: " + usefulMessage(error);
                }
            }
            return mProtectedUidSummary;
        }

        @Override
        public void run() {
            ShellStreamHandle stream = null;
            BufferedReader reader = null;
            boolean expected = false;
            boolean restored = false;
            android.content.Context context = MagicDeskApplication.applicationContext();
            boolean registered = false;
            try {
                android.content.IntentFilter filter = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON);
                context.registerReceiver(mScreenReceiver, filter, android.content.Context.RECEIVER_EXPORTED);
                registered = true;
                mWork = ShellAccess.acquireBackgroundWork(0, 0, false, new android.os.Binder());
                if (mDesktopDisplayId > 0) {
                    try {
                        mDisplayWork = ShellAccess.acquireBackgroundWork(mDesktopDisplayId, 0, false, new android.os.Binder());
                    } catch (IOException | RuntimeException error) {
                        Log.w(TAG, "Optional display application protection unavailable", error);
                    }
                }
                protectionSummary();
                stream = ShellAccess.openHeartbeatStream(
                        AppProcessCommand.exec(
                                GUARD_COMMAND,
                                mRestoreOperation));
                mStream = stream;
                if (mRestoreRequested) {
                    requestRestore();
                }
                reader = new BufferedReader(
                        new InputStreamReader(stream.inputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PhoneDisplayGuardCommand.READY.equals(line)) {
                        if (mRestoreRequested) {
                            requestRestore();
                            continue;
                        }
                        mGuardReady = true;
                        mReady.countDown();
                        onReady(this);
                    } else if (PhoneDisplayGuardCommand.RESTORED.equals(line)) {
                        expected = true;
                        restored = true;
                    } else if (line.startsWith(
                            PhoneDisplayGuardCommand.ERROR)) {
                        mFailure = line;
                    } else if (!line.isEmpty()) {
                        Log.d(TAG, line);
                    }
                }
                expected = expected || mRestoreRequested;
                if (!expected && mGuardReady
                        && "guard exited before ready".equals(mFailure)) {
                    mFailure = "guard stream ended unexpectedly";
                }
            } catch (IOException | RuntimeException error) {
                mFailure = usefulMessage(error);
                expected = mRestoreRequested;
            } finally {
                if (registered) context.unregisterReceiver(mScreenReceiver);
                mGuardReady = false;
                mReady.countDown();
                closeQuietly(reader);
                closeQuietly(stream);
                mStream = null;
                onStopped(this, expected, restored, mFailure);
                if (mDisplayWork != null) {
                    try { mDisplayWork.close(); }
                    catch (android.os.RemoteException | RuntimeException error) {
                        Log.w(TAG, "Could not release display background work", error);
                    }
                    mDisplayWork = null;
                }
                if (mWork != null) {
                    protectionSummary();
                    try { mWork.close(); }
                    catch (android.os.RemoteException | RuntimeException error) {
                        Log.w(TAG, "Could not release background work", error);
                    }
                    mWork = null;
                }
                mStopped.countDown();
            }
        }
    }
}
