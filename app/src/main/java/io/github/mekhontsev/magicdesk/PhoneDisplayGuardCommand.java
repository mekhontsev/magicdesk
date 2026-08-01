package io.github.mekhontsev.magicdesk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps display 0 physically off while its controlling shell stream is alive. */
public final class PhoneDisplayGuardCommand {
    static final String READY = "MAGICDESK_PHONE_DISPLAY_READY";
    static final String RESTORED = "MAGICDESK_PHONE_DISPLAY_RESTORED";
    static final String ERROR = "MAGICDESK_PHONE_DISPLAY_ERROR";
    static final String HEARTBEAT = "ping";
    static final String RESTORE = "restore";

    private static final long HEARTBEAT_TIMEOUT_MILLIS = 4_000L;
    private static final long WATCHDOG_INTERVAL_MILLIS = 500L;
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 16 * 1024;

    private final AtomicBoolean mDisplayOverrideActive =
            new AtomicBoolean();
    private final AtomicLong mLastHeartbeat = new AtomicLong();
    private final int mAppUid;
    private volatile boolean mFinished;
    private volatile NubiaCpuFreezerWorkingState.Session mFreezerSession;

    private PhoneDisplayGuardCommand(final int appUid) {
        mAppUid = appUid;
    }

    public static void main(final String[] arguments) {
        final PhoneDisplayGuardCommand guard;
        try {
            guard = new PhoneDisplayGuardCommand(parseAppUid(arguments));
        } catch (IllegalArgumentException error) {
            System.out.println(ERROR + " " + usefulMessage(error));
            return;
        }
        final Thread shutdownHook = new Thread(
                guard::restoreDisplay,
                "MagicDeskPhoneDisplayShutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            guard.run();
        } catch (IOException | ReflectiveOperationException error) {
            System.out.println(ERROR + " " + usefulMessage(error));
        } finally {
            guard.mFinished = true;
            guard.restoreDisplay();
            if (!guard.mDisplayOverrideActive.get()) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // The VM is already shutting down.
                }
            }
        }
    }

    private void run() throws IOException, ReflectiveOperationException {
        mFreezerSession = NubiaCpuFreezerWorkingState.begin(mAppUid);
        // Claim ownership before the command so every later exit path resets
        // even if the process dies immediately after DisplayManager accepts it.
        mDisplayOverrideActive.set(true);
        if (!requestDisplayPower("power-off")) {
            throw new IOException("DisplayManager rejected power-off for display 0");
        }
        mLastHeartbeat.set(android.os.SystemClock.elapsedRealtime());
        startWatchdog();
        System.out.println(READY);
        System.out.flush();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (HEARTBEAT.equals(line)) {
                    mFreezerSession.refresh();
                    mLastHeartbeat.set(
                            android.os.SystemClock.elapsedRealtime());
                } else if (RESTORE.equals(line)) {
                    if (restoreDisplay()) {
                        System.out.println(RESTORED);
                    } else {
                        System.out.println(ERROR + " power-reset-failed");
                    }
                    System.out.flush();
                    return;
                }
            }
        }
    }

    private void startWatchdog() {
        final Thread watchdog = new Thread(() -> {
            while (!mFinished && mDisplayOverrideActive.get()) {
                final long idleMillis =
                        android.os.SystemClock.elapsedRealtime()
                                - mLastHeartbeat.get();
                if (idleMillis > HEARTBEAT_TIMEOUT_MILLIS) {
                    System.out.println(ERROR + " heartbeat-timeout");
                    System.out.flush();
                    restoreDisplay();
                    System.exit(2);
                    return;
                }
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MILLIS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "MagicDeskPhoneDisplayWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private boolean restoreDisplay() {
        if (mDisplayOverrideActive.compareAndSet(true, false)) {
            if (!requestDisplayPower("power-reset")) {
                mDisplayOverrideActive.set(true);
                System.err.println(ERROR + " power-reset-failed");
                return false;
            }
        }
        final NubiaCpuFreezerWorkingState.Session session = mFreezerSession;
        if (session == null || session.close()) {
            mFreezerSession = null;
            return true;
        }
        System.err.println(ERROR + " cfreezer-working-state-release-failed");
        return false;
    }

    private static int parseAppUid(final String[] arguments) {
        if (arguments == null || arguments.length != 1) {
            throw new IllegalArgumentException("expected application UID");
        }
        final int uid;
        try {
            uid = Integer.parseInt(arguments[0]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid application UID", error);
        }
        if (uid < 10_000) {
            throw new IllegalArgumentException("invalid application UID " + uid);
        }
        return uid;
    }

    private static boolean requestDisplayPower(final String operation) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "/system/bin/cmd", "display", operation, "0")
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(
                            process,
                            COMMAND_TIMEOUT_MILLIS,
                            MAX_COMMAND_OUTPUT_BYTES);
            if (result.exitCode == 0) {
                return true;
            }
            System.err.println(ERROR + " " + operation
                    + " exit=" + result.exitCode
                    + " output=" + result.output.trim());
        } catch (IOException error) {
            System.err.println(ERROR + " " + operation
                    + " " + usefulMessage(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println(ERROR + " " + operation + " interrupted");
        } finally {
            if (process != null) {
                process.destroy();
                try {
                    process.waitFor(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return false;
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
