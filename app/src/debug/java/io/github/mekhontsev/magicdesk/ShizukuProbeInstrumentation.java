package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public final class ShizukuProbeInstrumentation extends Instrumentation {
    private static final long BINDER_TIMEOUT_SECONDS = 5;
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
                final Context context = getTargetContext().getApplicationContext();
                ShellAccess.initialize();
                awaitShizukuBinder();
                result.putString("shizuku_probe", ShellAccess.probeCapabilities());
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
                result.putString("shizuku_probe", "probe=failed | " + message);
                finish(Activity.RESULT_CANCELED, result);
            }
        }, "MagicDeskShizukuProbe");
        thread.setDaemon(true);
        thread.start();
    }

    private static void awaitShizukuBinder() throws InterruptedException {
        if (Shizuku.pingBinder()) {
            return;
        }
        final CountDownLatch received = new CountDownLatch(1);
        final Shizuku.OnBinderReceivedListener listener = received::countDown;
        Shizuku.addBinderReceivedListenerSticky(listener);
        try {
            received.await(BINDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            Shizuku.removeBinderReceivedListener(listener);
        }
    }

    private static String probePhoneScreen()
            throws IOException, InterruptedException {
        final int serviceUid = ShellAccess.connectAndGetUid();
        if (serviceUid != ShellAccess.SHELL_UID) {
            throw new IOException(
                    "Shizuku must run as shell UID 2000; found UID "
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
