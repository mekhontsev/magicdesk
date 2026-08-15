package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/** Commands that apply to the current folder rather than a selected item. */
final class FileManagerBackgroundContextMenu {
    interface Actions {
        void newFile();

        void newFolder();

        void paste();

        void refresh();

        void openConsole();
    }

    private FileManagerBackgroundContextMenu() {
    }

    static PopupWindow show(
            final Activity activity,
            final View anchor,
            final float rawX,
            final float rawY,
            final String path,
            final boolean canPaste,
            final Actions actions) {
        final DesktopUiFactory ui = new DesktopUiFactory(activity);
        final LinearLayout panel = FileItemContextMenu.createPanel(activity, ui);
        final TextView title = new TextView(activity);
        title.setText(path);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.START);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final PopupWindow[] holder = new PopupWindow[1];
        final Runnable dismiss = () -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
        };
        FileItemContextMenu.addAction(
                panel, ui, R.string.action_new_file,
                DesktopUiFactory.COLOR_CYAN, true,
                dismiss, actions::newFile);
        FileItemContextMenu.addAction(
                panel, ui, R.string.action_new_folder,
                DesktopUiFactory.COLOR_CYAN, true,
                dismiss, actions::newFolder);
        FileItemContextMenu.addAction(
                panel, ui, R.string.file_manager_paste,
                DesktopUiFactory.COLOR_PANEL_ALT, canPaste,
                dismiss, actions::paste);
        FileItemContextMenu.addAction(
                panel, ui, R.string.action_refresh,
                DesktopUiFactory.COLOR_PANEL_ALT, true,
                dismiss, actions::refresh);
        FileItemContextMenu.addAction(
                panel, ui, R.string.file_manager_console,
                DesktopUiFactory.COLOR_PANEL_ALT, true,
                dismiss, actions::openConsole);

        final int screenWidth = activity.getResources()
                .getDisplayMetrics().widthPixels;
        final int screenHeight = activity.getResources()
                .getDisplayMetrics().heightPixels;
        final int margin = ui.dp(12);
        final int width = Math.min(
                ui.dp(310), Math.max(ui.dp(250), screenWidth - margin * 2));
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int height = Math.min(
                panel.getMeasuredHeight(), Math.max(ui.dp(220),
                        screenHeight - margin * 2));
        final ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final PopupWindow popup = new PopupWindow(scroll, width, height, true);
        holder[0] = popup;
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                0x00000000));
        popup.setOutsideTouchable(true);
        popup.setElevation(ui.dp(8));
        final int left = clamp(Math.round(rawX), margin,
                Math.max(margin, screenWidth - width - margin));
        final int top = clamp(Math.round(rawY), margin,
                Math.max(margin, screenHeight - height - margin));
        popup.showAtLocation(
                anchor.getRootView(), Gravity.TOP | Gravity.START, left, top);
        return popup;
    }

    private static int clamp(final int value, final int minimum,
            final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
