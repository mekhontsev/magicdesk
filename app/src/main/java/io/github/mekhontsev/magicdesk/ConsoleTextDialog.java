package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/** The same editable preview for Android Share and the session paperclip. */
final class ConsoleTextDialog {
    interface Sender { void send(String text, String fileName, Consumer<Throwable> complete); }

    static void show(Activity activity, String initialText, View recipient, int fileCount,
            Sender sender, Runnable dismissed) {
        var content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(pad, 0, pad, 0);
        if (recipient != null) content.addView(recipient);
        if (fileCount > 0) {
            var files = new TextView(activity);
            files.setText(activity.getResources().getQuantityString(R.plurals.console_files_count, fileCount, fileCount));
            content.addView(files);
        }
        var text = new EditText(activity);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        text.setMinLines(3); text.setMaxLines(8);
        text.setText(initialText);
        text.setHint(R.string.console_text);
        content.addView(text, new LinearLayout.LayoutParams(-1, -2));
        var asFile = new CheckBox(activity);
        asFile.setText(R.string.console_text_file);
        content.addView(asFile);
        var name = new EditText(activity);
        name.setSingleLine(true);
        name.setText("note.txt");
        name.setHint(R.string.file_manager_name_hint);
        name.setVisibility(View.GONE);
        content.addView(name);
        asFile.setOnCheckedChangeListener((button, checked) -> name.setVisibility(checked ? View.VISIBLE : View.GONE));
        var status = new TextView(activity);
        content.addView(status);
        var scroll = new android.widget.ScrollView(activity);
        scroll.setFillViewport(true); scroll.addView(content);
        AlertDialog dialog = UiDialogs.builder(activity).setTitle(R.string.console_share_target).setView(scroll)
                .setPositiveButton(android.R.string.paste, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnDismissListener(d -> dismissed.run());
        dialog.show();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        var send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        var cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        send.setOnClickListener(view -> {
            String value = text.getText().toString();
            String filename = asFile.isChecked() ? name.getText().toString() : "";
            try {
                if (asFile.isChecked()) ConsoleSessionFiles.safeName(filename);
                if (value.isEmpty() && filename.isEmpty() && fileCount == 0) return;
            } catch (IllegalArgumentException error) { name.setError(error.getMessage()); return; }
            send.setEnabled(false); cancel.setEnabled(false); dialog.setCancelable(false);
            if (recipient != null) recipient.setEnabled(false);
            status.setText(R.string.console_content_sending);
            sender.send(value, filename, failure -> activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure == null) { dialog.dismiss(); return; }
                send.setEnabled(true); cancel.setEnabled(true); dialog.setCancelable(true);
                if (recipient != null) recipient.setEnabled(true);
                status.setText(failure instanceof java.util.concurrent.CancellationException ? ""
                        : ShellAccess.usefulMessage(failure));
            }));
        });
    }
}
