package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

/** Start's user-shortcut action, separate from package installation and application lifetime. */
final class TermuxShortcutDialog {
    static void confirmDelete(Activity activity, StartMenuEntry entry) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.action_delete_shortcut)
                .setMessage(activity.getString(R.string.delete_termux_shortcut_confirmation, entry.label))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> delete(activity, entry))
                .show();
    }

    private static void delete(Activity activity, StartMenuEntry entry) {
        try {
            if (entry.recent != null) RecentApplications.requireEnvironment(activity, entry.recent);
            final var endpoint = TermuxIntegration.inspect(activity);
            final String path = entry.desktopApplication.desktopFilePath;
            TermuxDesktopEntries.delete(activity.getApplicationContext(), endpoint, path, (result, failure) -> {
                if (failure != null || result == null || !result.success()) {
                    failed(activity, failure != null ? ShellAccess.usefulMessage(failure)
                            : result == null ? "No Termux result" : result.usefulMessage());
                    return;
                }
                X11Sessions.forgetLaunchSource(endpoint.packageName, path);
                WaylandSessions.forgetLaunchSource(endpoint.packageName, path);
                RecentApplications.removeSource(activity, endpoint.packageName, path, error -> {
                    if (error != null) {
                        failed(activity, ShellAccess.usefulMessage(error));
                        return;
                    }
                    ApplicationCatalog.get(activity).termuxApplicationsChanged();
                });
            });
        } catch (RuntimeException error) { failed(activity, ShellAccess.usefulMessage(error)); }
    }

    private static void failed(Activity activity, String detail) {
        if (!activity.isFinishing() && !activity.isDestroyed())
            Toast.makeText(activity, activity.getString(R.string.delete_shortcut_failed, detail), Toast.LENGTH_LONG).show();
    }
    private TermuxShortcutDialog() { }
}
