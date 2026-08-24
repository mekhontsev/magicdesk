package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Creates terminal Application desktop entries from a shared editor. */
final class DesktopCommandApplicationDialog {
    interface Listener {
        void onCreated(DesktopFileInfo file);
    }

    static final class InitialValues {
        final String name;
        final String command;
        final DesktopExecBackend backend;
        final String workingDirectory;

        InitialValues(
                final String name,
                final String command,
                final DesktopExecBackend backend,
                final String workingDirectory) {
            this.name = name == null ? "" : name;
            this.command = command == null ? "" : command;
            this.backend = backend == null
                    ? DesktopExecBackend.SHELL : backend;
            this.workingDirectory = workingDirectory == null
                    ? "" : workingDirectory;
        }

        static InitialValues empty(
                final String workingDirectory,
                final DesktopExecBackend backend) {
            return new InitialValues("", "", backend, workingDirectory);
        }

        static InitialValues fromFile(final ShellFileInfo file) {
            return fromFile(
                    file.name,
                    file.mimeType,
                    file.absolutePath,
                    file.executable);
        }

        static InitialValues fromFile(
                final String name,
                final String mimeType,
                final String absolutePath,
                final boolean executable) {
            if (!executable && !ShellScriptLauncher.supports(
                    name, mimeType, false)) {
                throw new IllegalArgumentException(
                        "file is not executable");
            }
            final String command = ShellScriptLauncher.supports(
                    name, mimeType, false)
                    ? ShellScriptLauncher.command(absolutePath)
                    : ShellCommandLine.quote(absolutePath);
            return new InitialValues(
                    DesktopCommandApplicationDraft.displayName(name),
                    command,
                    DesktopExecBackend.SHELL,
                    ShellScriptLauncher.workingDirectory(absolutePath));
        }
    }

    private static final ExecutorService CREATOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskApplicationCreator");
                thread.setDaemon(true);
                return thread;
            });

    private DesktopCommandApplicationDialog() {
    }

    static void show(
            final Activity activity,
            final InitialValues initial,
            final Listener listener) {
        final DesktopUiFactory ui = new DesktopUiFactory(activity);
        final LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8));

        final EditText name = field(
                activity, form, R.string.command_app_name, initial.name);
        final EditText command = field(
                activity, form, R.string.command_app_command, initial.command);
        command.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        command.setTypeface(Typeface.MONOSPACE);
        final EditText directory = field(
                activity,
                form,
                R.string.command_app_working_directory,
                initial.workingDirectory);
        directory.setTypeface(Typeface.MONOSPACE);

        label(activity, form, R.string.command_app_backend);
        final Spinner backend = spinner(
                activity,
                R.array.command_app_backends,
                initial.backend == DesktopExecBackend.TERMUX ? 1 : 0);
        form.addView(backend, matchWrap());

        label(activity, form, R.string.command_app_file_arguments);
        final Spinner arguments = spinner(
                activity, R.array.command_app_file_arguments_values, 0);
        form.addView(arguments, matchWrap());
        final EditText mimeTypes = field(
                activity, form, R.string.command_app_mime_types, "");

        final ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(form, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.command_app_title)
                .setView(scroll)
                .setPositiveButton(R.string.action_create, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        if (activity instanceof DesktopShellActivity) {
            ((DesktopShellActivity) activity).configureOverlayDialog(dialog);
        }
        dialog.setOnShowListener(ignored -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (name.getText().toString().trim().isEmpty()) {
                        name.setError(activity.getString(
                                R.string.desktop_entry_name_required));
                        return;
                    }
                    if (command.getText().toString().trim().isEmpty()) {
                        command.setError(activity.getString(
                                R.string.command_app_command_required));
                        return;
                    }
                    final String workingDirectory =
                            directory.getText().toString().trim();
                    if (!workingDirectory.isEmpty()
                            && !workingDirectory.startsWith("/")) {
                        directory.setError(activity.getString(
                                R.string.command_app_directory_invalid));
                        return;
                    }
                    try {
                        DesktopMimeTypes.parse(
                                mimeTypes.getText().toString().trim());
                    } catch (IllegalArgumentException error) {
                        mimeTypes.setError(activity.getString(
                                R.string.command_app_mime_invalid));
                        return;
                    }
                    final DesktopCommandApplicationDraft draft =
                            new DesktopCommandApplicationDraft(
                                    name.getText().toString(),
                                    command.getText().toString(),
                                    backend.getSelectedItemPosition() == 1
                                            ? DesktopExecBackend.TERMUX
                                            : DesktopExecBackend.SHELL,
                                    workingDirectory,
                                    DesktopCommandApplicationDraft.FileArguments
                                            .values()[arguments
                                                    .getSelectedItemPosition()],
                                    mimeTypes.getText().toString());
                    final DesktopApplicationShortcut shortcut;
                    try {
                        shortcut = draft.build();
                    } catch (IllegalArgumentException error) {
                        mimeTypes.setError(error.getMessage());
                        return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setEnabled(false);
                    create(activity, shortcut, listener, dialog);
                }));
        dialog.show();
    }

    private static void create(
            final Activity activity,
            final DesktopApplicationShortcut shortcut,
            final Listener listener,
            final AlertDialog dialog) {
        CREATOR.execute(() -> {
            DesktopFileInfo created = null;
            Throwable failure = null;
            try {
                created = DesktopEntryFile.createApplication(shortcut);
            } catch (IOException | RuntimeException error) {
                failure = error;
            }
            final DesktopFileInfo result = created;
            final Throwable error = failure;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (error != null) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setEnabled(true);
                    Toast.makeText(
                            activity,
                            activity.getString(
                                    R.string.command_app_create_failed,
                                    ShellAccess.usefulMessage(error)),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                Toast.makeText(
                        activity,
                        activity.getString(
                                R.string.command_app_created,
                                shortcut.name),
                        Toast.LENGTH_SHORT).show();
                if (listener != null) {
                    listener.onCreated(result);
                }
            });
        });
    }

    private static EditText field(
            final Activity activity,
            final LinearLayout form,
            final int labelResource,
            final String value) {
        label(activity, form, labelResource);
        final EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setText(value);
        form.addView(field, matchWrap());
        return field;
    }

    private static void label(
            final Activity activity,
            final LinearLayout form,
            final int resource) {
        final TextView label = new TextView(activity);
        label.setText(resource);
        label.setTextSize(12);
        final LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(activity, 10), 0, 0);
        form.addView(label, params);
    }

    private static Spinner spinner(
            final Activity activity,
            final int values,
            final int selected) {
        final Spinner spinner = new Spinner(activity);
        final ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                activity,
                values,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        return spinner;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(final Activity activity, final int value) {
        return Math.round(value * activity.getResources()
                .getDisplayMetrics().density);
    }
}
