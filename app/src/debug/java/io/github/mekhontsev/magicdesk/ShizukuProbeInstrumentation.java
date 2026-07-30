package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public final class ShizukuProbeInstrumentation extends Instrumentation {
    private static final long BINDER_TIMEOUT_SECONDS = 5;

    @Override
    public void onCreate(final Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        final Thread thread = new Thread(() -> {
            final Bundle result = new Bundle();
            try {
                final Context context = getTargetContext().getApplicationContext();
                ShizukuAccess.initialize(context);
                awaitShizukuBinder();
                result.putString("shizuku_probe", ShizukuAccess.probeCapabilities());
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
}
