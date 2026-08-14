package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns the fail-open physical display override while the shell stream is alive. */
final class PhoneDisplayGuard {
    private static final String TAG = "MagicDeskPhoneDisplay";
    private static final String GUARD_COMMAND =
            "io.github.mekhontsev.magicdesk.PhoneDisplayGuardCommand";
    private static final String DISPLAY_HELP =
            "/system/bin/cmd display help";
    private static final String DISPLAY_COMMAND =
            "/system/bin/cmd display ";
    private static final long START_TIMEOUT_MILLIS = 6_000L;
    private static final long STOP_TIMEOUT_MILLIS = 3_000L;

    private static final Object LOCK = new Object();
    private static Session sSession;
    private static int sGeneration;
    private static volatile String sRestoreOperation;

    private PhoneDisplayGuard() {
    }

    static boolean enable() {
        final String restoreOperation = resolveRestoreOperation();
        if (restoreOperation == null) {
            Log.w(TAG, "DisplayManager has no supported display restore command");
            return false;
        }
        final Session session;
        final boolean startSession;
        synchronized (LOCK) {
            if (sSession == null) {
                session = new Session(++sGeneration, restoreOperation);
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
            Log.w(TAG, "Phone display guard stopped: " + failure);
            CompatibilityDiagnostics.record(
                    "NUBIA-SCREEN-003",
                    "The phone-screen guard stopped",
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
        final String restoreOperation = resolveRestoreOperation();
        if (restoreOperation == null) {
            return false;
        }
        try {
            ShellAccess.run(DISPLAY_COMMAND + restoreOperation + " 0");
            return true;
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
            String operation = selectRestoreOperation(
                    ShellAccess.executeForConsole(DISPLAY_HELP).output);
            if (operation == null && isRecognizedRestoreProbe(
                    ShellAccess.executeForConsole(
                            DISPLAY_COMMAND
                                    + PhoneDisplayGuardCommand.POWER_RESET))) {
                operation = PhoneDisplayGuardCommand.POWER_RESET;
            }
            if (operation == null && isRecognizedRestoreProbe(
                    ShellAccess.executeForConsole(
                            DISPLAY_COMMAND
                                    + PhoneDisplayGuardCommand.POWER_ON))) {
                operation = PhoneDisplayGuardCommand.POWER_ON;
            }
            if (operation != null) {
                sRestoreOperation = operation;
            }
            return operation;
        } catch (IOException error) {
            Log.w(TAG, "Cannot inspect display power commands", error);
            return null;
        }
    }

    static String selectRestoreOperation(final String help) {
        if (help == null) {
            return null;
        }
        if (help.contains(PhoneDisplayGuardCommand.POWER_RESET)) {
            return PhoneDisplayGuardCommand.POWER_RESET;
        }
        if (help.contains(PhoneDisplayGuardCommand.POWER_ON)) {
            return PhoneDisplayGuardCommand.POWER_ON;
        }
        return null;
    }

    static boolean isRecognizedRestoreProbe(
            final ShellAccess.CommandResult result) {
        if (result == null || result.output == null) {
            return false;
        }
        final String output = result.output.toLowerCase(java.util.Locale.ROOT);
        return output.contains("no displayid specified")
                && !output.contains("unknown command");
    }

    private static void publishState(final boolean screenOff) {
        if (!ConsoleModeState.setPhoneScreenOff(screenOff)) {
            return;
        }
        MagicDeskRuntimeService.refreshNotificationIfRunning();
        DesktopRuntimeBridge.refreshDesktopControls();
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
        private final CountDownLatch mReady = new CountDownLatch(1);
        private final CountDownLatch mStopped = new CountDownLatch(1);
        private volatile boolean mRestoreRequested;
        private volatile boolean mGuardReady;
        private volatile String mFailure = "guard exited before ready";
        private volatile ShellStreamHandle mStream;

        Session(final int generation, final String restoreOperation) {
            mGeneration = generation;
            mRestoreOperation = restoreOperation;
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

        @Override
        public void run() {
            ShellStreamHandle stream = null;
            BufferedReader reader = null;
            boolean expected = false;
            try {
                stream = ShellAccess.openHeartbeatStream(
                        AppProcessCommand.exec(
                                GUARD_COMMAND,
                                Integer.toString(android.os.Process.myUid())
                                        + " " + mRestoreOperation));
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
            } catch (IOException error) {
                mFailure = usefulMessage(error);
                expected = mRestoreRequested;
            } finally {
                mGuardReady = false;
                mReady.countDown();
                closeQuietly(reader);
                closeQuietly(stream);
                mStream = null;
                onStopped(this, expected, mFailure);
                mStopped.countDown();
            }
        }
    }
}
