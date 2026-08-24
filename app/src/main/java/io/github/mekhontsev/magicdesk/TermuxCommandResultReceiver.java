package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Receives bounded results from Termux's documented RUN_COMMAND API. */
public final class TermuxCommandResultReceiver extends BroadcastReceiver {
    private static final String ACTION =
            BuildConfig.APPLICATION_ID + ".TERMUX_COMMAND_RESULT";
    private static final String EXTRA_REQUEST_ID = "requestId";
    private static final String EXTRA_RESULT = "result";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Integer, PendingResult> PENDING = new HashMap<>();

    static Registration register(
            final Context context,
            final long timeoutMillis,
            final TermuxIntegration.ResultCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("result callback is required");
        }
        final int requestId = nextRequestId();
        final Runnable timeout = () -> complete(
                requestId,
                null,
                new TimeoutException("Termux command result timed out"));
        synchronized (PENDING) {
            PENDING.put(requestId, new PendingResult(callback, timeout));
        }
        MAIN.postDelayed(timeout, Math.max(1L, timeoutMillis));
        final Intent result = new Intent(context,
                TermuxCommandResultReceiver.class)
                .setAction(ACTION)
                .putExtra(EXTRA_REQUEST_ID, requestId);
        return new Registration(
                requestId,
                PendingIntent.getBroadcast(
                        context,
                        requestId,
                        result,
                        PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_MUTABLE));
    }

    static void cancel(final Registration registration) {
        if (registration == null) {
            return;
        }
        registration.pendingIntent.cancel();
        final PendingResult pending;
        synchronized (PENDING) {
            pending = PENDING.remove(registration.requestId);
        }
        if (pending != null) {
            MAIN.removeCallbacks(pending.timeout);
        }
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        final int requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1);
        final Bundle bundle = intent.getBundleExtra(EXTRA_RESULT);
        complete(
                requestId,
                TermuxIntegration.CommandResult.fromBundle(bundle),
                null);
    }

    private static int nextRequestId() {
        final int value = NEXT_ID.getAndIncrement();
        if (value > 0) {
            return value;
        }
        NEXT_ID.set(2);
        return 1;
    }

    private static void complete(
            final int requestId,
            final TermuxIntegration.CommandResult result,
            final Throwable error) {
        final PendingResult pending;
        synchronized (PENDING) {
            pending = PENDING.remove(requestId);
        }
        if (pending == null) {
            return;
        }
        MAIN.removeCallbacks(pending.timeout);
        MAIN.post(() -> pending.callback.onResult(result, error));
    }

    private static final class PendingResult {
        final TermuxIntegration.ResultCallback callback;
        final Runnable timeout;

        PendingResult(
                final TermuxIntegration.ResultCallback callback,
                final Runnable timeout) {
            this.callback = callback;
            this.timeout = timeout;
        }
    }

    static final class Registration {
        final int requestId;
        final PendingIntent pendingIntent;

        Registration(
                final int requestId,
                final PendingIntent pendingIntent) {
            this.requestId = requestId;
            this.pendingIntent = pendingIntent;
        }
    }
}
