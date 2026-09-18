package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;

/** Dialog-scoped entry selection: discovered PRoot or an explicit user-owned launcher. */
final class LinuxEnvironmentPicker extends LinearLayout {
    private final Spinner method;
    private final LinearLayout prootFields;
    private final LinearLayout scriptFields;
    private final EditText script;
    private final EditText keyboard;
    private final LinearLayout keyboardFields;
    private final Spinner choices;
    private final TextView status;
    private TermuxIntegration.Endpoint endpoint;
    private TermuxCommandResultReceiver.Registration request;
    private int generation;
    private boolean active;
    private DesktopExecBackend backend = DesktopExecBackend.TERMUX;

    LinuxEnvironmentPicker(Context context) {
        super(context);
        setOrientation(VERTICAL);
        TextView methodTitle = new TextView(context);
        methodTitle.setText(R.string.command_app_linux_method);
        methodTitle.setTextSize(12);
        addView(methodTitle);
        method = new Spinner(context);
        var methods = ArrayAdapter.createFromResource(context, R.array.command_app_linux_methods,
                android.R.layout.simple_spinner_item);
        methods.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        method.setAdapter(methods);
        addView(method, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        prootFields = new LinearLayout(context);
        prootFields.setOrientation(VERTICAL);
        addView(prootFields);
        TextView title = new TextView(context);
        title.setText(R.string.command_app_proot_environment);
        title.setTextSize(12);
        prootFields.addView(title);
        choices = new Spinner(context);
        choices.setContentDescription(context.getString(R.string.command_app_proot_environment));
        prootFields.addView(choices, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        status = new TextView(context);
        prootFields.addView(status);
        scriptFields = new LinearLayout(context);
        scriptFields.setOrientation(VERTICAL);
        TextView scriptTitle = new TextView(context);
        scriptTitle.setText(R.string.command_app_linux_script);
        scriptTitle.setTextSize(12);
        scriptFields.addView(scriptTitle);
        script = new EditText(context);
        script.setSingleLine(true);
        script.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scriptFields.addView(script, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        scriptFields.setVisibility(View.GONE);
        addView(scriptFields);
        keyboardFields = new LinearLayout(context);
        keyboardFields.setOrientation(VERTICAL);
        TextView keyboardTitle = new TextView(context);
        keyboardTitle.setText(R.string.x11_keyboard_directory);
        keyboardFields.addView(keyboardTitle);
        keyboard = new EditText(context);
        keyboard.setSingleLine(true);
        keyboardFields.addView(keyboard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        keyboardFields.setVisibility(View.GONE);
        addView(keyboardFields);
        method.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (active) load();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) load(); else cancel();
    }

    void setBackend(DesktopExecBackend value) {
        if (backend == value) return;
        backend = value;
        if (value == DesktopExecBackend.SHELL) method.setSelection(1);
        method.setEnabled(value == DesktopExecBackend.TERMUX);
        if (active) load();
    }

    void setGraphical(boolean graphical) {
        keyboardFields.setVisibility(graphical && backend == DesktopExecBackend.SHELL ? View.VISIBLE : View.GONE);
    }

    private void load() {
        cancel();
        boolean proot = method.getSelectedItemPosition() == 0;
        prootFields.setVisibility(proot ? View.VISIBLE : View.GONE);
        scriptFields.setVisibility(proot ? View.GONE : View.VISIBLE);
        endpoint = backend == DesktopExecBackend.TERMUX ? TermuxIntegration.inspect(getContext()) : null;
        if (!proot) return;
        final int expected = generation;
        choices.setAdapter(null);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.command_app_proot_loading);
        try {
            request = TermuxIntegration.runBackgroundShellCommandForResult(getContext(), endpoint,
                    "proot-distro list --quiet", "MagicDesk PRoot environments", endpoint.homeDirectory,
                    15_000, (result, failure) -> {
                        if (expected != generation) return;
                        request = null;
                        try {
                            if (failure != null) throw new IllegalStateException(ShellAccess.usefulMessage(failure));
                            if (result == null || !result.success()) throw new IllegalStateException(
                                    result == null ? "No proot-distro result" : result.usefulMessage());
                            List<String> names = LinuxLaunchRecipe.parseInstalledProot(result.stdout);
                            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                                    android.R.layout.simple_spinner_item, names);
                            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                            choices.setAdapter(adapter);
                            status.setText(R.string.command_app_proot_empty);
                            status.setVisibility(names.isEmpty() ? View.VISIBLE : View.GONE);
                        } catch (RuntimeException error) { status.setText(ShellAccess.usefulMessage(error)); }
                    });
        } catch (RuntimeException error) { status.setText(ShellAccess.usefulMessage(error)); }
    }

    LinuxLaunchRecipe.Environment selected() {
        if (method.getSelectedItemPosition() == 1)
            return new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.SCRIPT, script.getText().toString(),
                    backend, keyboard.getText().toString());
        Object selected = choices.getSelectedItem();
        if (selected == null) throw new IllegalArgumentException(
                getContext().getString(R.string.command_app_proot_select));
        return new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.PROOT, selected.toString());
    }

    TermuxIntegration.Endpoint endpoint() { return endpoint; }

    private void cancel() {
        generation++;
        TermuxCommandResultReceiver.cancel(request);
        request = null;
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }
}
