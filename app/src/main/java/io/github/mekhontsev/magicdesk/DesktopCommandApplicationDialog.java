package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
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

/** Creates command Application desktop entries from a shared editor. */
final class DesktopCommandApplicationDialog {
    interface Listener {
        void onCreated();
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
        create(activity, initial, listener).show();
    }

    static AlertDialog create(
            final Activity activity,
            final InitialValues initial,
            final Listener listener) {
        final DesktopUiFactory ui = new DesktopUiFactory(activity);
        final LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8));

        final EditText name = field(
                activity, form, R.string.command_app_name, initial.name);

        label(activity, form, R.string.command_app_backend);
        final Spinner backend = spinner(activity, R.array.command_app_backends,
                initial.backend.ordinal());
        form.addView(backend, matchWrap());
        final LinearLayout graphicsFields = new LinearLayout(activity);
        graphicsFields.setOrientation(LinearLayout.VERTICAL);
        graphicsFields.setVisibility(View.GONE);
        label(activity, graphicsFields, R.string.command_app_graphics);
        final Spinner protocol = spinner(activity, R.array.command_app_graphics_protocols, 0);
        graphicsFields.addView(protocol, matchWrap());
        final android.widget.CheckBox wholeDesktop = new android.widget.CheckBox(activity);
        wholeDesktop.setText(R.string.graphics_whole_desktop);
        graphicsFields.addView(wholeDesktop, matchWrap());
        form.addView(graphicsFields, matchWrap());
        final LinuxEnvironmentPicker linux = new LinuxEnvironmentPicker(activity);
        linux.setVisibility(View.GONE);
        form.addView(linux, matchWrap());
        final LinearLayout presentationFields = new LinearLayout(activity);
        presentationFields.setOrientation(LinearLayout.VERTICAL);
        presentationFields.setVisibility(View.GONE);
        label(activity, presentationFields, R.string.command_app_presentation);
        final Spinner presentation = spinner(activity, R.array.command_app_presentations, 0);
        presentationFields.addView(presentation, matchWrap());
        final EditText linuxUser = field(activity, presentationFields, R.string.command_app_linux_user, "");
        linuxUser.setHint(R.string.command_app_linux_user_hint);
        linuxUser.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(presentationFields, matchWrap());

        final EditText command = field(
                activity, form, R.string.command_app_command, initial.command);
        command.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        command.setTypeface(Typeface.MONOSPACE);
        final TextView directoryLabel = label(activity, form, R.string.command_app_working_directory);
        final EditText directory = new EditText(activity);
        directory.setSingleLine(true);
        directory.setText(initial.workingDirectory);
        form.addView(directory, matchWrap());
        directory.setTypeface(Typeface.MONOSPACE);

        final LinearLayout fileFields = new LinearLayout(activity);
        fileFields.setOrientation(LinearLayout.VERTICAL);
        label(activity, fileFields, R.string.command_app_file_arguments);
        final Spinner arguments = spinner(
                activity, R.array.command_app_file_arguments_values, 0);
        fileFields.addView(arguments, matchWrap());
        final EditText mimeTypes = field(
                activity, fileFields, R.string.command_app_mime_types, "");
        form.addView(fileFields, matchWrap());

        backend.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean wasLinux;
            private String hostDirectory = initial.workingDirectory;
            private String guestDirectory = "";

            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean isLinux = position >= 3;
                graphicsFields.setVisibility(position == 2 || (isLinux && presentation.getSelectedItemPosition() != 0)
                        ? View.VISIBLE : View.GONE);
                wholeDesktop.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
                linux.setBackend(position == 4 ? DesktopExecBackend.SHELL : DesktopExecBackend.TERMUX);
                linux.setActive(isLinux);
                linux.setGraphical(presentation.getSelectedItemPosition() != 0);
                presentationFields.setVisibility(isLinux ? View.VISIBLE : View.GONE);
                fileFields.setVisibility(isLinux ? View.GONE : View.VISIBLE);
                directoryLabel.setText(isLinux ? R.string.command_app_guest_directory
                        : R.string.command_app_working_directory);
                command.setHint(isLinux ? activity.getString(R.string.command_app_linux_command_hint) : null);
                if (isLinux != wasLinux) {
                    if (isLinux) {
                        hostDirectory = directory.getText().toString();
                        directory.setText(guestDirectory);
                    } else {
                        guestDirectory = directory.getText().toString();
                        directory.setText(hostDirectory);
                    }
                }
                wasLinux = isLinux;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        presentation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                linux.setGraphical(position != 0);
                if (backend.getSelectedItemPosition() >= 3)
                    graphicsFields.setVisibility(position != 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

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
        dialog.setOnShowListener(ignored -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (name.getText().toString().trim().isEmpty()) {
                        name.setError(activity.getString(
                                R.string.desktop_entry_name_required));
                        return;
                    }
                    final boolean isLinux = backend.getSelectedItemPosition() >= 3;
                    if (command.getText().toString().trim().isEmpty()
                            && !(isLinux && presentation.getSelectedItemPosition() == 0)) {
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
                        if (isLinux) linux.selected();
                        DesktopMimeTypes.parse(
                                isLinux ? "" : mimeTypes.getText().toString().trim());
                    } catch (IllegalArgumentException error) {
                        command.setError(error.getMessage());
                        return;
                    }
                    final DesktopApplicationShortcut shortcut;
                    try {
                        shortcut = isLinux ? LinuxLaunchRecipe.build(name.getText().toString(),
                                linux.selected(), command.getText().toString(), workingDirectory,
                                linuxUser.getText().toString(),
                                LinuxLaunchRecipe.Presentation.values()[presentation.getSelectedItemPosition()],
                                GraphicalProtocol.values()[protocol.getSelectedItemPosition()])
                                : new DesktopCommandApplicationDraft(
                                    name.getText().toString(),
                                    command.getText().toString(),
                                    backend.getSelectedItemPosition() == 0 ? DesktopExecBackend.SHELL : DesktopExecBackend.TERMUX,
                                    workingDirectory,
                                    DesktopCommandApplicationDraft.FileArguments
                                            .values()[arguments
                                                    .getSelectedItemPosition()],
                                    mimeTypes.getText().toString()).build();
                    } catch (IllegalArgumentException error) {
                        command.setError(error.getMessage());
                        return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setEnabled(false);
                    if (isLinux && linux.endpoint() != null) {
                        try {
                            TermuxDesktopEntries.create(activity.getApplicationContext(), linux.endpoint(),
                                    shortcut, (result, failure) -> completed(activity, shortcut, listener, dialog, true,
                                            failure != null ? failure : result != null && result.success() ? null
                                                    : new IOException(result == null ? "No Termux result" : result.usefulMessage())));
                        } catch (RuntimeException error) {
                            completed(activity, shortcut, listener, dialog, true, error);
                        }
                    } else create(activity, backend.getSelectedItemPosition() == 2
                            ? new DesktopApplicationShortcut(shortcut.name, shortcut.icon, shortcut.exec, null, "",
                                    shortcut.launchMode, false, shortcut.execBackend, false, shortcut.workingDirectory,
                                    shortcut.mimeTypes).withGraphics(new GraphicalLaunchOptions(
                                            GraphicalProtocol.values()[protocol.getSelectedItemPosition()], wholeDesktop.isChecked(), "", "", "")) : shortcut,
                            listener, dialog);
                }));
        return dialog;
    }

    private static void create(
            final Activity activity,
            final DesktopApplicationShortcut shortcut,
            final Listener listener,
            final AlertDialog dialog) {
        CREATOR.execute(() -> {
            Throwable failure = null;
            try {
                DesktopEntryFile.createApplication(shortcut);
            } catch (IOException | RuntimeException error) {
                failure = error;
            }
            final Throwable error = failure;
            activity.runOnUiThread(() -> completed(activity, shortcut, listener, dialog, false, error));
        });
    }

    private static void completed(Activity activity, DesktopApplicationShortcut shortcut,
            Listener listener, AlertDialog dialog, boolean termux, Throwable error) {
        if (error == null && termux) ApplicationCatalog.get(activity).termuxApplicationsChanged();
        if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
        if (error != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            Toast.makeText(activity, activity.getString(R.string.command_app_create_failed,
                    ShellAccess.usefulMessage(error)), Toast.LENGTH_LONG).show();
            return;
        }
        dialog.dismiss();
        Toast.makeText(activity, activity.getString(termux ? R.string.command_app_termux_created
                : R.string.command_app_created, shortcut.name), Toast.LENGTH_SHORT).show();
        if (listener != null) listener.onCreated();
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

    private static TextView label(
            final Activity activity,
            final LinearLayout form,
            final int resource) {
        final TextView label = new TextView(activity);
        label.setText(resource);
        label.setTextSize(12);
        final LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(activity, 10), 0, 0);
        form.addView(label, params);
        return label;
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
