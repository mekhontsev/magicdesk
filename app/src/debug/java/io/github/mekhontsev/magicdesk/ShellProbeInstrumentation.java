package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShellProbeInstrumentation extends Instrumentation {
    private static final long SERVICE_TIMEOUT_MILLIS = 60_000;
    private static final long OPERATION_TIMEOUT_SECONDS = 10;
    private boolean mProbePhoneScreen;

    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        mProbePhoneScreen = arguments != null
                && "true".equals(arguments.getString("phone_screen"));
        start();
    }

    @Override
    public void onStart() {
        final Thread thread = new Thread(() -> {
            final Bundle result = new Bundle();
            try {
                ShellAccess.initialize();
                awaitShellService();
                result.putString("shell_probe", ShellAccess.probeCapabilities());
                if (mProbePhoneScreen) {
                    result.putString(
                            "phone_screen_probe",
                            probePhoneScreen());
                }
                finish(Activity.RESULT_OK, result);
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                final String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                result.putString("shell_probe", "probe=failed | " + message);
                finish(Activity.RESULT_CANCELED, result);
            }
        }, "MagicDeskShellProbe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void awaitShellService() throws InterruptedException, IOException {
        final Object lock = new Object();
        final ShellAccess.StateListener listener = snapshot -> {
            synchronized (lock) { lock.notifyAll(); }
        };
        ShellAccess.addStateListener(listener);
        try {
            synchronized (lock) {
                final long deadline = android.os.SystemClock.uptimeMillis() + SERVICE_TIMEOUT_MILLIS;
                while (!ShellAccess.isReady()) {
                    final long remaining = deadline - android.os.SystemClock.uptimeMillis();
                    if (remaining <= 0) throw new IOException(ShellAccess.currentSnapshot().error);
                    EventDrivenWaits.await(lock, EventDrivenWaits.Reason.SERVICE_BINDING, remaining);
                }
            }
        } finally {
            ShellAccess.removeStateListener(listener);
        }
    }

    private static String probePhoneScreen()
            throws IOException, InterruptedException {
        final int serviceUid = ShellAccess.connectAndGetUid();
        if (serviceUid != ShellAccess.SHELL_UID) {
            throw new IOException(
                    "The service must run as shell UID 2000; found UID "
                            + serviceUid);
        }

        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();
        DesktopOperations.setPhoneScreenOff(false, value -> {
            success.set(value);
            completed.countDown();
        });
        if (!completed.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("phone-screen safe probe timed out");
        }
        if (!success.get()) {
            throw new IOException("phone-screen safe probe failed");
        }
        return "granted | uid=" + serviceUid;
    }
}
