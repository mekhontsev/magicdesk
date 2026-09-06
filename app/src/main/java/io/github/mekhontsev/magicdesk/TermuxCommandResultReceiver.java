package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** Receives bounded results from Termux's documented RUN_COMMAND API. */
public final class TermuxCommandResultReceiver extends BroadcastReceiver {
    private static final String ACTION =
            BuildConfig.APPLICATION_ID + ".TERMUX_COMMAND_RESULT";
    private static final String EXTRA_RESULT = "result";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Registration> PENDING = new HashMap<>();

    static Registration register(
            final Context context,
            final long timeoutMillis,
            final TermuxIntegration.ResultCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("result callback is required");
        }
        // PendingIntents survive process death. Use an Intent identity that a
        // later process cannot reuse, rather than a process-local counter.
        final String requestId = "magicdesk-termux-result:" + UUID.randomUUID();
        final Intent result = new Intent(context,
                TermuxCommandResultReceiver.class)
                .setAction(ACTION)
                .setData(Uri.parse(requestId));
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, result,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
        final Runnable timeout = () -> complete(
                requestId,
                null,
                new TimeoutException("Termux command result timed out"));
        final Registration registration = new Registration(
                requestId, pendingIntent, callback, timeout);
        synchronized (PENDING) {
            PENDING.put(requestId, registration);
            MAIN.postDelayed(timeout, Math.max(1L, timeoutMillis));
        }
        return registration;
    }

    static void cancel(final Registration registration) {
        if (registration == null) {
            return;
        }
        take(registration.requestId);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        final String requestId = intent.getDataString();
        final Bundle bundle = intent.getBundleExtra(EXTRA_RESULT);
        complete(
                requestId,
                TermuxIntegration.CommandResult.fromBundle(bundle),
                null);
    }

    private static void complete(
            final String requestId,
            final TermuxIntegration.CommandResult result,
            final Throwable error) {
        final Registration pending = take(requestId);
        if (pending != null) {
            MAIN.post(() -> pending.callback.onResult(result, error));
        }
    }

    private static Registration take(final String requestId) {
        final Registration pending;
        synchronized (PENDING) {
            pending = PENDING.remove(requestId);
        }
        if (pending != null) {
            MAIN.removeCallbacks(pending.timeout);
            pending.pendingIntent.cancel();
        }
        return pending;
    }

    static final class Registration {
        final String requestId;
        final PendingIntent pendingIntent;
        final TermuxIntegration.ResultCallback callback;
        final Runnable timeout;

        Registration(
                final String requestId,
                final PendingIntent pendingIntent,
                final TermuxIntegration.ResultCallback callback,
                final Runnable timeout) {
            this.requestId = requestId;
            this.pendingIntent = pendingIntent;
            this.callback = callback;
            this.timeout = timeout;
        }
    }
}
