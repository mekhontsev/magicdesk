package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;
import io.github.mekhontsev.magicdesk.ShellTaskUidReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps display 0 physically off while its controlling shell stream is alive. */
public final class PhoneDisplayGuardCommand {
    static final String READY = "MAGICDESK_PHONE_DISPLAY_READY";
    static final String RESTORED = "MAGICDESK_PHONE_DISPLAY_RESTORED";
    static final String ERROR = "MAGICDESK_PHONE_DISPLAY_ERROR";
    static final String PROTECTED_UIDS = "MAGICDESK_PHONE_DISPLAY_UIDS";
    static final String HEARTBEAT = "ping";
    static final String RESTORE = "restore";
    static final String POWER_RESET = "power-reset";
    static final String POWER_ON = "power-on";

    private static final long HEARTBEAT_TIMEOUT_MILLIS = 4_000L;
    private static final long WATCHDOG_INTERVAL_MILLIS = 500L;
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 16 * 1024;

    private final AtomicBoolean mDisplayOverrideActive =
            new AtomicBoolean();
    private final AtomicLong mLastHeartbeat = new AtomicLong();
    private final int mAppUid;
    private final String mRestoreOperation;
    private final int mDesktopDisplayId;
    private final int mInputPackageUid;
    private final Map<Integer, NubiaCpuFreezerWorkingState.Session>
            mFreezerSessions = new LinkedHashMap<>();
    private Set<Integer> mReportedUids = new LinkedHashSet<>();
    private final Set<Integer> mProtectionFailures = new LinkedHashSet<>();
    private String mLastTaskReadFailure;
    private String mLastPointerRefreshFailure;
    private volatile boolean mFinished;

    private PhoneDisplayGuardCommand(
            final int appUid,
            final String restoreOperation,
            final int desktopDisplayId,
            final int inputPackageUid) {
        mAppUid = appUid;
        mRestoreOperation = restoreOperation;
        mDesktopDisplayId = desktopDisplayId;
        mInputPackageUid = inputPackageUid;
    }

    public static void main(final String[] arguments) {
        final PhoneDisplayGuardCommand guard;
        try {
            validateArguments(arguments);
            guard = new PhoneDisplayGuardCommand(
                    parseAppUid(arguments[0]),
                    parseRestoreOperation(arguments[1]),
                    parseDesktopDisplayId(arguments[2]),
                    parseOptionalAppUid(arguments[3]));
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
        refreshFreezerState();
        // Claim ownership before the command so every later exit path resets
        // even if the process dies immediately after DisplayManager accepts it.
        mDisplayOverrideActive.set(true);
        if (!requestDisplayPower("power-off")) {
            throw new IOException("DisplayManager rejected power-off for display 0");
        }
        refreshPointerViewport();
        mLastHeartbeat.set(android.os.SystemClock.elapsedRealtime());
        startWatchdog();
        System.out.println(READY);
        System.out.flush();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (HEARTBEAT.equals(line)) {
                    refreshFreezerState();
                    refreshPointerViewport();
                    mLastHeartbeat.set(
                            android.os.SystemClock.elapsedRealtime());
                } else if (RESTORE.equals(line)) {
                    if (restoreDisplay()) {
                        System.out.println(RESTORED);
                    } else {
                        System.out.println(ERROR + " "
                                + mRestoreOperation + "-failed");
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
            if (!requestDisplayPower(mRestoreOperation)) {
                mDisplayOverrideActive.set(true);
                System.err.println(ERROR + " "
                        + mRestoreOperation + "-failed");
                return false;
            }
        }
        boolean released = true;
        synchronized (mFreezerSessions) {
            for (final NubiaCpuFreezerWorkingState.Session session
                    : mFreezerSessions.values()) {
                released &= session.close();
            }
            mFreezerSessions.clear();
        }
        if (!released) {
            System.err.println(ERROR + " cfreezer-working-state-release-failed");
        }
        return released;
    }

    private void refreshFreezerState() throws ReflectiveOperationException {
        final Set<Integer> desiredUids = new LinkedHashSet<>();
        desiredUids.add(Integer.valueOf(mAppUid));
        if (mInputPackageUid >= 10_000) {
            desiredUids.add(Integer.valueOf(mInputPackageUid));
        }
        try {
            desiredUids.addAll(ShellTaskUidReader.read(mDesktopDisplayId));
            mLastTaskReadFailure = null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Keeping MagicDesk itself alive preserves fail-open screen recovery.
            final String failure = usefulMessage(error);
            if (!failure.equals(mLastTaskReadFailure)) {
                mLastTaskReadFailure = failure;
                System.err.println(
                        "MagicDesk phone display: could not read desktop UIDs: "
                                + failure);
            }
        }

        synchronized (mFreezerSessions) {
            desiredUids.addAll(mFreezerSessions.keySet());
            for (final Integer uid : desiredUids) {
                final NubiaCpuFreezerWorkingState.Session existing =
                        mFreezerSessions.get(uid);
                if (existing != null) {
                    try {
                        existing.refresh();
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        if (uid.intValue() == mAppUid) {
                            throw error;
                        }
                        existing.close();
                        mFreezerSessions.remove(uid);
                        mProtectionFailures.add(uid);
                    }
                    continue;
                }
                try {
                    mFreezerSessions.put(
                            uid, NubiaCpuFreezerWorkingState.begin(uid.intValue()));
                    mProtectionFailures.remove(uid);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    if (uid.intValue() == mAppUid) {
                        throw error;
                    }
                    if (mProtectionFailures.add(uid)) {
                        System.err.println(
                                "MagicDesk phone display: could not protect UID "
                                        + uid + ": " + usefulMessage(error));
                    }
                }
            }

            // Keep the union for the whole screen-off interval. Releasing a
            // briefly absent task can freeze shared input state mid-session.
            final Set<Integer> protectedUids =
                    new LinkedHashSet<>(mFreezerSessions.keySet());
            if (!protectedUids.equals(mReportedUids)) {
                mReportedUids = protectedUids;
                System.out.println(PROTECTED_UIDS + " "
                        + joinUids(protectedUids));
                System.out.flush();
            }
        }
    }

    private static String joinUids(final Set<Integer> uids) {
        final StringBuilder output = new StringBuilder();
        for (final Integer uid : uids) {
            if (output.length() > 0) {
                output.append(',');
            }
            output.append(uid.intValue());
        }
        return output.toString();
    }

    private static void validateArguments(final String[] arguments) {
        if (arguments == null || arguments.length != 4) {
            throw new IllegalArgumentException(
                    "expected application UID, restore operation, desktop display, and input UID");
        }
    }

    private static int parseDesktopDisplayId(final String argument) {
        final int displayId;
        try {
            displayId = Integer.parseInt(argument);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "invalid desktop display ID", error);
        }
        if (displayId <= 0) {
            throw new IllegalArgumentException(
                    "invalid desktop display ID " + displayId);
        }
        return displayId;
    }

    private static int parseAppUid(final String argument) {
        final int uid;
        try {
            uid = Integer.parseInt(argument);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid application UID", error);
        }
        if (uid < 10_000) {
            throw new IllegalArgumentException("invalid application UID " + uid);
        }
        return uid;
    }

    private static int parseOptionalAppUid(final String argument) {
        if ("-1".equals(argument)) {
            return -1;
        }
        return parseAppUid(argument);
    }

    private void refreshPointerViewport() {
        try {
            NubiaMouseController.createOrUpdateViewport();
            mLastPointerRefreshFailure = null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            final String failure = usefulMessage(error);
            if (!failure.equals(mLastPointerRefreshFailure)) {
                mLastPointerRefreshFailure = failure;
                System.err.println(
                        "MagicDesk phone display: could not refresh pointer viewport: "
                                + failure);
            }
        }
    }

    private static String parseRestoreOperation(final String operation) {
        if (!isRestoreOperation(operation)) {
            throw new IllegalArgumentException(
                    "unsupported display restore operation " + operation);
        }
        return operation;
    }

    static boolean isRestoreOperation(final String operation) {
        return POWER_RESET.equals(operation) || POWER_ON.equals(operation);
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
