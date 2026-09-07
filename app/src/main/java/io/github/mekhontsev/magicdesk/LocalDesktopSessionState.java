package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

final class LocalDesktopSessionState {
    private static final String PREFERENCES =
            "magicdesk_local_desktop_session";
    private static final String CLEANUP_PENDING = "cleanup_pending";
    private static final String RECOVER_PHONE_TASKS = "recover_phone_tasks";

    private LocalDesktopSessionState() {
    }

    @SuppressLint("ApplySharedPref")
    static void markCleanupPending(final Context context) {
        // Persist before task mutation so recovery survives an immediate crash.
        preferences(context).edit()
                .putBoolean(CLEANUP_PENDING, true)
                .putBoolean(RECOVER_PHONE_TASKS, DesktopCompatibilitySettings.current().enabled(
                        DesktopCompatibilityPolicy.Option.PHONE_TASK_RECOVERY))
                .commit();
    }

    static boolean requiresTaskRecovery(final Context context) {
        return preferences(context).getBoolean(RECOVER_PHONE_TASKS, false);
    }

    static boolean isCleanupPending(final Context context) {
        return preferences(context).getBoolean(CLEANUP_PENDING, false);
    }

    static void clearCleanupPending(final Context context) {
        preferences(context).edit()
                .remove(CLEANUP_PENDING)
                .remove(RECOVER_PHONE_TASKS)
                .apply();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
    }
}
