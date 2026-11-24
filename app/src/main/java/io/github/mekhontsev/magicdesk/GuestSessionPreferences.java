package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Launch preferences belong to a console or catalog entry of one captured executor. */
final class GuestSessionPreferences {
    static boolean guestPath(String path) { return path != null && path.startsWith("/magicdesk-guest/"); }
    static String console(GuestEnvironmentCatalog.Entry entry) { return "console:" + entry.store(); }

    private static String key(Context context, DesktopExecBackend backend, String source) {
        return new CommandExecution(context, backend).scope + "|" + source;
    }
    static GuestSessionOptions load(Context context, DesktopExecBackend backend, String source) {
        String key = key(context, backend, source);
        var prefs = context.getSharedPreferences("guest_session_options", Context.MODE_PRIVATE);
        return new GuestSessionOptions(prefs.getString(key + ":user", ""), prefs.getBoolean(key + ":boot", false));
    }

    static void show(Activity activity, String title, DesktopExecBackend backend, String source) {
        var ui = new DesktopUiFactory(activity);
        var current = load(activity, backend, source);
        String key = key(activity, backend, source);
        var form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
        var label = new TextView(activity);
        label.setText(R.string.command_app_linux_user);
        var user = new EditText(activity);
        user.setSingleLine(true);
        user.setHint(R.string.command_app_linux_user_hint);
        user.setText(current.user());
        user.setId(android.view.View.generateViewId());
        label.setLabelFor(user.getId());
        var boot = new CheckBox(activity);
        boot.setText(R.string.guest_start_services);
        boot.setChecked(current.boot());
        form.addView(label); form.addView(user); form.addView(boot);
        var dialog = UiDialogs.builder(activity).setTitle(title).setView(form)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                var options = new GuestSessionOptions(user.getText().toString(), boot.isChecked());
                var edit = activity.getSharedPreferences("guest_session_options", Context.MODE_PRIVATE).edit();
                if (options.user().isEmpty()) edit.remove(key + ":user"); else edit.putString(key + ":user", options.user());
                if (options.boot()) edit.putBoolean(key + ":boot", true); else edit.remove(key + ":boot");
                edit.apply();
                dialog.dismiss();
            } catch (IllegalArgumentException e) { user.setError(e.getMessage()); }
        }));
        dialog.show();
    }
    private GuestSessionPreferences() { }
}
