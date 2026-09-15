package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** One output configuration snapshot. The caller revalidates the output on apply. */
final class DisplayOutputDialog {
    private DisplayOutputDialog() { }

    static void show(Activity activity, PlatformProjectionDriver.ModeSelection selection,
            boolean configurable, Consumer<String> apply) {
        final List<PlatformProjectionDriver.Mode> modes = new ArrayList<>();
        if (selection.systemDefaultAvailable) {
            modes.add(new PlatformProjectionDriver.Mode("", activity.getString(R.string.external_display_system_native)));
        }
        modes.addAll(selection.availableModes);
        final String current = activity.getString(R.string.external_display_current_mode,
                selection.current == null ? activity.getString(R.string.state_unavailable) : selection.current.displayLabel);
        if (!configurable || modes.isEmpty()) {
            new AlertDialog.Builder(activity).setTitle(R.string.external_display_resolution)
                    .setMessage(current).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(activity).setTitle(current)
                .setSingleChoiceItems(modes.stream().map(mode -> mode.displayLabel).toArray(String[]::new),
                        outputModeIndex(selection, modes), (dialog, index) -> {
                            apply.accept(modes.get(index).timingKey);
                            dialog.dismiss();
                        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    static int outputModeIndex(PlatformProjectionDriver.ModeSelection selection,
            List<PlatformProjectionDriver.Mode> modes) {
        final String timing = selection.systemDefaultSelected ? ""
                : selection.target == null ? null : selection.target.timingKey;
        for (int index = 0; index < modes.size(); index++) {
            if (modes.get(index).timingKey.equals(timing)) { return index; }
        }
        return -1;
    }
}
