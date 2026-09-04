package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.os.Build;

/** Applies release-compatible background-start policy to Activity PendingIntents. */
final class AndroidPendingIntentOptions {
    private AndroidPendingIntentOptions() {
    }

    static void allowCreatorStart(final ActivityOptions options) {
        options.setPendingIntentCreatorBackgroundActivityStartMode(
                modeForSdk(Build.VERSION.SDK_INT, false));
    }

    static void allowSenderStart(
            final ActivityOptions options,
            final boolean onlyIfVisible) {
        options.setPendingIntentBackgroundActivityStartMode(
                modeForSdk(Build.VERSION.SDK_INT, onlyIfVisible));
    }

    @SuppressWarnings("deprecation")
    static int modeForSdk(final int sdk, final boolean onlyIfVisible) {
        if (sdk >= 36) {
            return onlyIfVisible
                    ? ActivityOptions
                            .MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS;
        }
        return ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
    }
}
