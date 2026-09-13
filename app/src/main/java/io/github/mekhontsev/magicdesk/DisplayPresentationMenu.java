package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;
import java.util.Arrays;
import java.util.function.Consumer;

/** Optional presentation actions share the existing display selection, not another home screen. */
final class DisplayPresentationMenu {
    private DisplayPresentationMenu() { }

    static void show(Activity activity, View anchor, DesktopDisplayInfo selected,
            DesktopDisplayInfo[] catalog, Consumer<DesktopDisplayInfo> startPortable) {
        final PopupMenu menu = new PopupMenu(activity, anchor);
        final boolean source = DisplayPresentationMode.forSource(selected) == DisplayPresentationMode.DIRECT;
        final DisplayPresentations.Session session = DisplayPresentations.forSource(selected.id);
        final var otherDisplays = Arrays.stream(catalog).filter(d -> d.id != selected.id).toList();
        menu.getMenu().add(R.string.display_show_on).setEnabled(!otherDisplays.isEmpty())
                .setOnMenuItemClickListener(item -> {
                    new AlertDialog.Builder(activity).setTitle(R.string.display_show_on)
                            .setItems(otherDisplays.stream().map(DisplayPresentationMenu::label).toArray(String[]::new),
                                    (dialog, index) -> open(activity, selected, otherDisplays.get(index)))
                            .setNegativeButton(android.R.string.cancel, null).show();
                    return true;
                });
        menu.getMenu().add(source ? R.string.display_park : R.string.display_close_viewer).setEnabled(session != null)
                .setOnMenuItemClickListener(item -> { DisplayPresentations.park(session); return true; });
        menu.getMenu().add(R.string.display_viewer).setEnabled(!otherDisplays.isEmpty())
                .setOnMenuItemClickListener(item -> {
                    new AlertDialog.Builder(activity).setTitle(R.string.display_viewer_source)
                            .setItems(otherDisplays.stream().map(DisplayPresentationMenu::label).toArray(String[]::new),
                                    (dialog, index) -> open(activity, otherDisplays.get(index), selected))
                            .setNegativeButton(android.R.string.cancel, null).show();
                    return true;
                });
        if (!source) {
            menu.getMenu().add(R.string.display_start_portable)
                    .setEnabled(RuntimeCapabilities.supportsDesktop(android.os.Build.VERSION.SDK_INT))
                    .setOnMenuItemClickListener(item -> { startPortable.accept(selected); return true; });
        }
        menu.show();
    }

    private static void open(Activity activity, DesktopDisplayInfo source, DesktopDisplayInfo output) {
        DisplayPresentations.open(activity, source.id, output.id, false, error -> {
            if (error != null) Toast.makeText(activity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
        });
    }

    private static String label(DesktopDisplayInfo display) {
        return display.name + " [" + display.id + "]";
    }
}
