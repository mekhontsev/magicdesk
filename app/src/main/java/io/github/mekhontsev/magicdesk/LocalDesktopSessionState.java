package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

final class LocalDesktopSessionState {
    private static final String PREFERENCES =
            "magicdesk_local_desktop_session";
    private static final String CLEANUP_PENDING = "cleanup_pending";

    private LocalDesktopSessionState() {
    }

    static void markCleanupPending(final Context context) {
        preferences(context).edit()
                .putBoolean(CLEANUP_PENDING, true)
                .commit();
    }

    static boolean isCleanupPending(final Context context) {
        return preferences(context).getBoolean(CLEANUP_PENDING, false);
    }

    static void clearCleanupPending(final Context context) {
        preferences(context).edit()
                .remove(CLEANUP_PENDING)
                .apply();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
    }
}
