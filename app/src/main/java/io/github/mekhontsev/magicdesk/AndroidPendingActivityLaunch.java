package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.atomic.AtomicInteger;

/** Creates one-shot app-identity tokens for shell-owned task placement. */
final class AndroidPendingActivityLaunch {
    private static final AtomicInteger NEXT_REQUEST_CODE =
            new AtomicInteger(1);

    private AndroidPendingActivityLaunch() {
    }

    static PendingIntent create(
            final Context context,
            final Intent sourceIntent) {
        if (context == null || sourceIntent == null) {
            throw new IllegalArgumentException(
                    "pending Activity launch requires context and Intent");
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        AndroidPendingIntentOptions.allowCreatorStart(options);
        return PendingIntent.getActivity(
                context,
                nextRequestCode(),
                new Intent(sourceIntent),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE,
                options.toBundle());
    }

    private static int nextRequestCode() {
        return NEXT_REQUEST_CODE.getAndUpdate(
                value -> value == Integer.MAX_VALUE ? 1 : value + 1);
    }
}
