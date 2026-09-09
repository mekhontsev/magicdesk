package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.net.SocketException;
import java.util.List;
import java.util.function.Consumer;

/** Local-only authorization UI; network access is never enabled by an MCP command. */
final class McpNetworkSettingsDialog {
    static void show(final Activity activity, final boolean enable,
            final Consumer<Boolean> saved, final Runnable dismissed) {
        final List<McpNetworkInterfaces.Binding> bindings;
        try {
            bindings = McpNetworkInterfaces.available();
        } catch (SocketException error) {
            saved.accept(false);
            return;
        }
        if (bindings.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage(R.string.settings_mcp_network_no_interface)
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener(dialog -> dismissed.run()).show();
            return;
        }
        final var settings = MagicDeskMcpPreferences.load(activity);
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        final int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        final TextView warning = new TextView(activity);
        warning.setText(R.string.settings_mcp_network_warning);
        content.addView(warning);
        final Spinner interfaces = new Spinner(activity);
        final ArrayAdapter<McpNetworkInterfaces.Binding> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, bindings);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        interfaces.setAdapter(adapter);
        interfaces.setContentDescription(activity.getString(R.string.settings_mcp_network_interface));
        for (int i = 0; i < bindings.size(); i++) {
            if (bindings.get(i).name.equals(settings.networkInterface)) interfaces.setSelection(i);
        }
        content.addView(interfaces);
        final EditText port = new EditText(activity);
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setHint(R.string.settings_mcp_network_port);
        port.setContentDescription(activity.getString(R.string.settings_mcp_network_port));
        port.setText(Integer.toString(settings.networkPort));
        content.addView(port);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_mcp_network_configure).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(ignored -> dismissed.run()).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    final int value;
                    try {
                        value = Integer.parseInt(port.getText().toString().trim());
                        if (value < 1024 || value > 65535) throw new NumberFormatException();
                    } catch (NumberFormatException error) {
                        port.setError(activity.getString(R.string.settings_mcp_network_invalid_port));
                        return;
                    }
                    final var binding = bindings.get(interfaces.getSelectedItemPosition());
                    final boolean stored = MagicDeskMcpPreferences.configureNetwork(
                            activity, binding.name, value);
                    final boolean enabled = !enable || (stored
                            && MagicDeskMcpPreferences.setNetworkEnabled(activity, true));
                    saved.accept(stored && enabled);
                    dialog.dismiss();
                }));
        dialog.show();
    }
}
