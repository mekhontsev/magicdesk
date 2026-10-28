package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A view of one captured session, never the owner of its files or picker result. */
final class ConsoleSessionFilesDialog {
    private final Activity activity;
    private final ConsoleSessionFiles files;
    private final Consumer<List<String>> insert;
    private final ListView list;
    private final TextView status;
    private final ImageButton add, remove, cancel;
    private final AlertDialog dialog;
    private List<ConsoleSessionFiles.Item> shown = List.of();
    private final java.util.Set<String> selected = new java.util.HashSet<>();
    private final Runnable refresh = this::refresh;

    static void show(Activity activity, ConsoleSessionFiles files, Consumer<List<String>> insert) {
        new ConsoleSessionFilesDialog(activity, files, insert);
    }

    private ConsoleSessionFilesDialog(Activity activity, ConsoleSessionFiles files, Consumer<List<String>> insert) {
        this.activity = activity; this.files = files; this.insert = insert;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout toolbar = new LinearLayout(activity);
        add = button(R.drawable.ic_add, R.string.console_files_add, this::addMenu);
        remove = button(R.drawable.ic_file_delete, R.string.action_delete, view -> {
            var selection = selectedItems();
            if (selection.isEmpty()) return;
            UiDialogs.builder(activity).setTitle(R.string.console_files_delete)
                    .setPositiveButton(R.string.action_delete, (d, which) -> files.remove(selection))
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
        cancel = button(R.drawable.ic_close, android.R.string.cancel, view -> files.cancel());
        toolbar.addView(add); toolbar.addView(remove); toolbar.addView(cancel);
        content.addView(toolbar);
        status = new TextView(activity);
        status.setMaxLines(2);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(status, new LinearLayout.LayoutParams(-1, dp(44)));
        list = new ListView(activity);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String key = shown.get(position).key();
            if (list.isItemChecked(position)) selected.add(key); else selected.remove(key);
            updateButtons();
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(220)));
        dialog = UiDialogs.builder(activity).setTitle(R.string.console_session_files).setView(content)
                .setPositiveButton(R.string.console_files_insert, (d, which) ->
                        insert.accept(selectedItems().stream().map(ConsoleSessionFiles.Item::path).toList()))
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnDismissListener(d -> files.unsubscribe(refresh));
        dialog.show();
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { files.unsubscribe(refresh); }
        });
        files.subscribe(refresh); refresh();
    }

    private List<ConsoleSessionFiles.Item> selectedItems() {
        return shown.stream().filter(item -> selected.contains(item.key())).toList();
    }

    private void refresh() {
        if (activity.isFinishing() || activity.isDestroyed()) { files.unsubscribe(refresh); return; }
        var state = files.state();
        if (state.closed()) { dialog.dismiss(); return; }
        if (!shown.equals(state.items())) {
            shown = state.items();
            selected.retainAll(shown.stream().map(ConsoleSessionFiles.Item::key).toList());
            var labels = new ArrayList<String>();
            for (var item : shown) labels.add(item.name() + "\n" + android.text.format.Formatter.formatFileSize(activity, item.bytes()));
            list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_multiple_choice, labels));
            for (int i = 0; i < shown.size(); i++) list.setItemChecked(i, selected.contains(shown.get(i).key()));
        }
        status.setText(!state.error().isEmpty() ? state.error() : state.busy()
                ? activity.getString(R.string.console_files_importing, state.progress())
                : shown.isEmpty() ? activity.getString(R.string.console_files_empty)
                : activity.getResources().getQuantityString(R.plurals.console_files_count, shown.size(), shown.size()));
        updateButtons();
    }

    private void updateButtons() {
        boolean busy = files.state().busy();
        add.setEnabled(!busy); remove.setEnabled(!busy && !selected.isEmpty()); cancel.setEnabled(busy);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy && !selected.isEmpty());
    }

    private void addMenu(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        int[] labels = {R.string.file_manager_import_files, R.string.console_files_directory, R.string.console_files_camera};
        var sources = ConsoleSessionFiles.Source.values();
        for (int i = 0; i < sources.length; i++) {
            var source = sources[i];
            menu.getMenu().add(labels[i]).setOnMenuItemClickListener(item -> {
                files.choose(source, activity.getDisplay() == null ? 0 : activity.getDisplay().getDisplayId()); return true;
            });
        }
        menu.getMenu().add(R.string.console_files_clipboard).setOnMenuItemClickListener(item -> {
            var payload = AndroidClipboardGateway.get(activity).readContent().content;
            if (payload != null && payload.hasUris()) files.importUris(payload.uris());
            else android.widget.Toast.makeText(activity, R.string.console_files_clipboard_empty, android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        menu.show();
    }

    private ImageButton button(int icon, int label, View.OnClickListener click) {
        ImageButton button = new ImageButton(activity);
        UiAppearance.icon(button, icon, UiColor.TEXT);
        button.setContentDescription(activity.getString(label));
        button.setTooltipText(activity.getString(label));
        android.util.TypedValue background = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        button.setOnClickListener(click);
        return button;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
