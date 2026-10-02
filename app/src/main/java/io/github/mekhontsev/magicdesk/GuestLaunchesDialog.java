package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import org.json.JSONObject;
import java.util.ArrayList;

/** On-demand view of native launch ownership, not another process manager. */
final class GuestLaunchesDialog {
    static void show(Activity activity, GuestEnvironmentCatalog.Entry environment) {
        var dialog = UiDialogs.builder(activity).setTitle(environment.name())
                .setMessage(R.string.guest_loading)
                .setNeutralButton(R.string.action_refresh, (picker, button) -> show(activity, environment))
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.show();
        try {
            var request = new CommandExecution(activity, DesktopExecBackend.SHELL).start(
                    GuestEnvironmentCatalog.command("image", "launches", environment.store()), "", "Guest launches", null,
                    (code, output, error) -> activity.runOnUiThread(() -> {
                        if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                        try {
                            if (error != null) throw new java.io.IOException("Guest launches unavailable", error);
                            if (code != 0) throw new java.io.IOException(output);
                            var launches = new ArrayList<JSONObject>();
                            for (String line : output.split("\n")) if (!line.isBlank()) launches.add(new JSONObject(line));
                            if (launches.isEmpty()) { dialog.setMessage(activity.getText(R.string.guest_no_launches)); return; }
                            String[] labels = new String[launches.size()];
                            for (int i = 0; i < labels.length; i++) {
                                var item = launches.get(i);
                                labels[i] = title(item) + "\nUID " + item.getLong("guestUid")
                                        + "  " + item.getString("launchId").substring(0, 8);
                            }
                            dialog.dismiss();
                            UiDialogs.builder(activity).setTitle(environment.name()).setItems(labels, (picker, index) -> {
                                var selected = launches.get(index);
                                UiDialogs.builder(activity).setTitle(R.string.guest_stop_launch)
                                        .setMessage(title(selected))
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .setPositiveButton(R.string.guest_stop_launch, (confirm, button) -> stop(activity, environment, selected.optString("launchId")))
                                        .show();
                            }).setNeutralButton(R.string.action_refresh, (picker, button) -> show(activity, environment))
                                    .setNegativeButton(android.R.string.cancel, null).show();
                        } catch (Exception failure) { dialog.setMessage(ShellAccess.usefulMessage(failure)); }
                    }));
            dialog.setOnDismissListener(ignored -> { try { request.close(); } catch (java.io.IOException ignoredError) { } });
        } catch (RuntimeException error) { dialog.setMessage(ShellAccess.usefulMessage(error)); }
    }

    private static void stop(Activity activity, GuestEnvironmentCatalog.Entry environment, String id) {
        try {
            new CommandExecution(activity, DesktopExecBackend.SHELL).start(
                GuestEnvironmentCatalog.command("image", "stop", environment.store(), id), "", "Stop guest launch", null,
                (code, output, error) -> activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (error != null || code != 0) failure(activity, error == null ? output : ShellAccess.usefulMessage(error));
                    else show(activity, environment);
                }));
        } catch (RuntimeException error) { failure(activity, ShellAccess.usefulMessage(error)); }
    }
    private static void failure(Activity activity, String message) {
        UiDialogs.builder(activity).setTitle(R.string.guest_stop_launch).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show();
    }
    private static String title(JSONObject item) {
        return item.optString("label").isEmpty() ? item.optString("program") : item.optString("label");
    }
    private GuestLaunchesDialog() { }
}
