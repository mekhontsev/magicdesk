package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import java.util.Arrays;
import java.util.List;

/** Choose a source for the selected output without changing either display's lifetime. */
final class DisplaySourceDialog {
    private DisplaySourceDialog() { }

    static void show(Activity activity, DesktopDisplayInfo output, DesktopDisplayInfo[] catalog) {
        final List<DesktopDisplayInfo> sources = Arrays.stream(catalog)
                .filter(d -> d.id != output.id).toList();
        if (sources.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.display_viewer_no_sources), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(activity).setTitle(R.string.display_show_another)
                .setItems(sources.stream().map(d -> label(activity, d)).toArray(String[]::new),
                        (dialog, index) -> DisplayPresentations.attachOutput(activity, sources.get(index),
                                output, reportFailure(activity)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static BuiltInWindowLauncher.Callback reportFailure(Activity activity) {
        return error -> {
            if (error != null) Toast.makeText(activity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
        };
    }

    private static String label(Activity activity, DesktopDisplayInfo display) {
        return display.name + " [" + display.id + "]\n" + activity.getString(
                DesktopRuntimeBridge.hasWorkspace(display.id) ? R.string.display_desktop_active : R.string.display_no_desktop);
    }
}
