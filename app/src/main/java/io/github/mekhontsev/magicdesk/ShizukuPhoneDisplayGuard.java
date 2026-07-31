package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns the fail-open physical display override used by the Shizuku backend. */
final class ShizukuPhoneDisplayGuard {
    private static final String TAG = "MagicDeskPhoneDisplay";
    private static final String GUARD_COMMAND =
            "io.github.mekhontsev.magicdesk.PhoneDisplayGuardCommand";
    private static final String POWER_RESET =
            "/system/bin/cmd display power-reset 0";
    private static final long START_TIMEOUT_MILLIS = 6_000L;
    private static final long STOP_TIMEOUT_MILLIS = 3_000L;

    private static final Object LOCK = new Object();
    private static Session sSession;
    private static int sGeneration;

    private ShizukuPhoneDisplayGuard() {
    }

    static boolean enable() {
        final Session session;
        final boolean startSession;
        synchronized (LOCK) {
            if (sSession == null) {
                session = new Session(++sGeneration);
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
        session.awaitStopped();
        clearSession(session);
        resetWithoutSession();
        return false;
    }

    static boolean disable() {
        final Session session;
        synchronized (LOCK) {
            session = sSession;
        }
        if (session != null) {
            session.requestRestore();
            if (!session.awaitStopped()) {
                session.closeStream();
            }
            clearSession(session);
        }

        // This idempotent reset confirms recovery even when the guarded
        // process disappeared before it could acknowledge the restore.
        final boolean reset = resetWithoutSession();
        publishState(false);
        return reset;
    }

    static void requestRestore() {
        final Session session;
        synchronized (LOCK) {
            session = sSession;
        }
        if (session != null) {
            session.requestRestore();
        }
    }

    static boolean isActive() {
        synchronized (LOCK) {
            return sSession != null && sSession.isReady();
        }
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
            final String failure) {
        final boolean wasCurrent = clearSession(session);
        if (wasCurrent) {
            if (!expected) {
                resetWithoutSession();
            }
            publishState(false);
        }
        if (!expected) {
            Log.w(TAG, "Shizuku phone display guard stopped: " + failure);
            CompatibilityDiagnostics.record(
                    "NUBIA-SCREEN-003",
                    "The Shizuku phone-screen guard stopped",
                    failure);
        }
    }

    private static boolean clearSession(final Session session) {
        synchronized (LOCK) {
            if (sSession != session) {
                return false;
            }
            sSession = null;
            return true;
        }
    }

    private static boolean resetWithoutSession() {
        try {
            ShizukuAccess.run(POWER_RESET);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "Cannot reset phone display", error);
            return false;
        }
    }

    private static void publishState(final boolean screenOff) {
        if (!ConsoleModeState.setShizukuPhoneScreenOff(screenOff)) {
            return;
        }
        MagicDeskRuntimeService.refreshNotificationIfRunning();
        DesktopRuntimeBridge.refreshConsoleControls();
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
        private final CountDownLatch mReady = new CountDownLatch(1);
        private final CountDownLatch mStopped = new CountDownLatch(1);
        private volatile boolean mRestoreRequested;
        private volatile boolean mGuardReady;
        private volatile String mFailure = "guard exited before ready";
        private volatile ShizukuAccess.StreamHandle mStream;
        private ShizukuHeartbeat mHeartbeat;

        Session(final int generation) {
            mGeneration = generation;
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
            final ShizukuAccess.StreamHandle stream = mStream;
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

        @Override
        public void run() {
            ShizukuAccess.StreamHandle stream = null;
            BufferedReader reader = null;
            boolean expected = false;
            try {
                stream = ShizukuAccess.openStream(
                        AppProcessCommand.exec(GUARD_COMMAND));
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
                        startHeartbeat(stream);
                    } else if (PhoneDisplayGuardCommand.RESTORED.equals(line)) {
                        expected = true;
                    } else if (line.startsWith(
                            PhoneDisplayGuardCommand.ERROR)) {
                        mFailure = line;
                    } else if (!line.isEmpty()) {
                        Log.d(TAG, line);
                    }
                }
                expected = expected || mRestoreRequested;
                if (!expected && mGuardReady) {
                    mFailure = "guard stream ended unexpectedly";
                }
            } catch (IOException error) {
                mFailure = usefulMessage(error);
                expected = mRestoreRequested;
            } finally {
                mGuardReady = false;
                mReady.countDown();
                closeQuietly(mHeartbeat);
                closeQuietly(reader);
                closeQuietly(stream);
                mStream = null;
                onStopped(this, expected, mFailure);
                mStopped.countDown();
            }
        }

        private void startHeartbeat(
                final ShizukuAccess.StreamHandle stream) {
            mHeartbeat = ShizukuHeartbeat.start(
                    "MagicDeskPhoneDisplayHeartbeat-" + mGeneration,
                    error -> {
                        mFailure = "heartbeat failed: "
                                + usefulMessage(error);
                        closeQuietly(stream);
                    },
                    stream);
        }
    }
}
