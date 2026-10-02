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

/** Dialog-scoped entry selection from the selected executor's environment catalog. */
final class LinuxEnvironmentPicker extends LinearLayout {
    private final Spinner method;
    private final LinearLayout prootFields;
    private final LinearLayout scriptFields;
    private final EditText script;
    private final TextView scriptTitle;
    private final EditText keyboard;
    private final LinearLayout keyboardFields;
    private final Spinner choices;
    private final TextView status;
    private TermuxIntegration.Endpoint endpoint;
    private TermuxCommandResultReceiver.Registration request;
    private java.io.Closeable guestRequest;
    private List<GuestEnvironmentCatalog.Entry> guests = List.of();
    private boolean graphical;
    private final TextView title;
    private int generation;
    private boolean active;
    private DesktopExecBackend backend = DesktopExecBackend.TERMUX;
    private List<LinuxLaunchRecipe.Kind> kinds = List.of(LinuxLaunchRecipe.Kind.PROOT, LinuxLaunchRecipe.Kind.SCRIPT);

    LinuxEnvironmentPicker(Context context) {
        super(context);
        setOrientation(VERTICAL);
        TextView methodTitle = new TextView(context);
        methodTitle.setText(R.string.command_app_linux_method);
        methodTitle.setTextSize(12);
        addView(methodTitle);
        method = new Spinner(context);
        updateMethods();
        addView(method, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        prootFields = new LinearLayout(context);
        prootFields.setOrientation(VERTICAL);
        addView(prootFields);
        title = new TextView(context);
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
        scriptTitle = new TextView(context);
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
        kinds = value == DesktopExecBackend.SHELL
                ? List.of(LinuxLaunchRecipe.Kind.MANAGED_GUEST, LinuxLaunchRecipe.Kind.SCRIPT, LinuxLaunchRecipe.Kind.GUEST)
                : List.of(LinuxLaunchRecipe.Kind.PROOT, LinuxLaunchRecipe.Kind.SCRIPT);
        updateMethods();
        if (active) load();
    }

    private void updateMethods() {
        String[] labels = getResources().getStringArray(R.array.command_app_linux_methods);
        var adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item,
                kinds.stream().map(kind -> kind == LinuxLaunchRecipe.Kind.MANAGED_GUEST
                        ? getContext().getString(R.string.guest_environments) : labels[kind.ordinal()]).toList());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        method.setAdapter(adapter);
    }

    void setGraphical(boolean graphical) {
        this.graphical = graphical;
        updateKeyboard();
    }

    private void updateKeyboard() {
        keyboardFields.setVisibility(graphical && backend == DesktopExecBackend.SHELL
                && kinds.get(Math.max(0, method.getSelectedItemPosition())) != LinuxLaunchRecipe.Kind.MANAGED_GUEST ? View.VISIBLE : View.GONE);
    }

    private void load() {
        cancel();
        var kind = kinds.get(Math.max(0, method.getSelectedItemPosition()));
        boolean proot = kind == LinuxLaunchRecipe.Kind.PROOT;
        boolean managed = kind == LinuxLaunchRecipe.Kind.MANAGED_GUEST;
        prootFields.setVisibility(proot || managed ? View.VISIBLE : View.GONE);
        scriptFields.setVisibility(proot || managed ? View.GONE : View.VISIBLE);
        title.setText(managed ? R.string.guest_environments : R.string.command_app_proot_environment);
        choices.setContentDescription(title.getText());
        updateKeyboard();
        scriptTitle.setText(kind == LinuxLaunchRecipe.Kind.GUEST ? R.string.command_app_guest_store : R.string.command_app_linux_script);
        endpoint = backend == DesktopExecBackend.TERMUX ? TermuxIntegration.inspect(getContext()) : null;
        if (!proot && !managed) return;
        final int expected = generation;
        choices.setAdapter(null);
        status.setVisibility(View.VISIBLE);
        status.setText(managed ? R.string.guest_loading : R.string.command_app_proot_loading);
        try {
            if (managed) {
                guestRequest = GuestEnvironmentCatalog.load(getContext(), (entries, error) -> post(() -> {
                    if (expected != generation) return;
                    guests = entries;
                    var adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, guests);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    choices.setAdapter(adapter);
                    status.setText(error == null ? getContext().getString(R.string.guest_empty) : ShellAccess.usefulMessage(error));
                    status.setVisibility(error != null || guests.isEmpty() ? View.VISIBLE : View.GONE);
                }));
                return;
            }
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
        var kind = kinds.get(Math.max(0, method.getSelectedItemPosition()));
        if (kind == LinuxLaunchRecipe.Kind.MANAGED_GUEST) {
            Object selection = choices.getSelectedItem();
            if (!(selection instanceof GuestEnvironmentCatalog.Entry item)) throw new IllegalArgumentException(getContext().getString(R.string.guest_select));
            return new LinuxLaunchRecipe.Environment(kind, item.store(), backend, "");
        }
        if (kind != LinuxLaunchRecipe.Kind.PROOT)
            return new LinuxLaunchRecipe.Environment(kind, script.getText().toString(),
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
        if (guestRequest != null) {
            try { guestRequest.close(); } catch (java.io.IOException ignored) { }
            guestRequest = null;
        }
        guests = List.of();
    }

    @Override protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }
}
