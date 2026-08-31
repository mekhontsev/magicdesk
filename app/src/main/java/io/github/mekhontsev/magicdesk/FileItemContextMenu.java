package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared file/folder context menu for the desktop and Files. */
final class FileItemContextMenu {
    interface Actions {
        void open();
        void openWith();
        void install();
        void runScript();
        void createTerminalApplication();
        void setWallpaper();
        void createDesktopShortcut();
        void copy();
        void cut();
        void rename();
        void delete();
        void copyPath();
        void properties();
    }

    static final class Target {
        final String name;
        final String mimeType;
        final boolean directory;
        final boolean canCreateDesktopShortcut;
        final boolean canCreateTerminalApplication;

        Target(
                final String name,
                final String mimeType,
                final boolean directory,
                final boolean canCreateDesktopShortcut,
                final boolean canCreateTerminalApplication) {
            this.name = name;
            this.mimeType = mimeType;
            this.directory = directory;
            this.canCreateDesktopShortcut = canCreateDesktopShortcut;
            this.canCreateTerminalApplication = canCreateTerminalApplication;
        }

        static Target from(final ShellFileInfo file) {
            return new Target(
                    file.name,
                    file.mimeType,
                    file.directory,
                    file.directory && !ShellDesktopDirectory.ABSOLUTE_PATH
                            .equals(file.absolutePath),
                    !file.directory && (file.executable
                            || ShellScriptLauncher.supports(file)));
        }

        static Target from(final DesktopFile file) {
            return new Target(
                    file.displayName(),
                    file.mimeType,
                    file.opensDirectory(),
                    false,
                    !file.directory && ShellScriptLauncher.supports(
                            file.name, file.mimeType, false));
        }
    }

    private FileItemContextMenu() {
    }

    static LinearLayout createPanel(
            final Activity activity, final DesktopUiFactory ui) {
        final LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8));
        panel.setBackground(ui.menuSurface());
        panel.setClickable(true);
        panel.setFocusable(true);
        return panel;
    }

    static PopupWindow showPopup(
            final Activity activity,
            final View anchor,
            final Target target,
            final Actions actions) {
        final DesktopUiFactory ui = new DesktopUiFactory(activity);
        final LinearLayout panel = createPanel(activity, ui);
        final int width = ui.menuWidth(
                activity.getResources().getDisplayMetrics().widthPixels,
                ui.dp(12));
        final PopupWindow[] popupHolder = new PopupWindow[1];
        populate(activity, ui, panel, target, actions, () -> {
            if (popupHolder[0] != null) {
                popupHolder[0].dismiss();
            }
        });
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(
                        width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        0, View.MeasureSpec.UNSPECIFIED));
        final int maxHeight = Math.max(
                ui.dp(240),
                activity.getResources().getDisplayMetrics().heightPixels
                        - ui.dp(24));
        final int height = Math.min(panel.getMeasuredHeight(), maxHeight);
        final ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(panel, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final PopupWindow popup = new PopupWindow(
                scroll,
                width,
                height,
                true);
        popupHolder[0] = popup;
        popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        popup.setOutsideTouchable(true);
        popup.setElevation(ui.dp(8));
        popup.showAsDropDown(anchor, 0, -anchor.getHeight());
        return popup;
    }

    static void populate(
            final Activity activity,
            final DesktopUiFactory ui,
            final LinearLayout panel,
            final Target target,
            final Actions actions,
            final Runnable dismiss) {
        panel.removeAllViews();
        final TextView title = ui.menuHeader(
                target.name, TextUtils.TruncateAt.END);
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addAction(panel, ui, R.string.action_open,
                DesktopUiFactory.COLOR_CYAN, true, dismiss, actions::open);
        addAction(panel, ui, R.string.file_manager_open_with,
                DesktopUiFactory.COLOR_PANEL_ALT, !target.directory,
                dismiss, actions::openWith);
        addAction(panel, ui, R.string.file_manager_install_apk,
                DesktopUiFactory.COLOR_PANEL_ALT,
                ShellPackageInstaller.supports(
                        target.name, target.mimeType, target.directory),
                dismiss, actions::install);
        addAction(panel, ui, R.string.file_manager_run_script,
                DesktopUiFactory.COLOR_PANEL_ALT,
                ShellScriptLauncher.supports(
                        target.name, target.mimeType, target.directory),
                dismiss, actions::runScript);
        if (target.canCreateTerminalApplication) {
            addAction(panel, ui, R.string.action_add_as_terminal_application,
                    DesktopUiFactory.COLOR_PANEL_ALT, true,
                    dismiss, actions::createTerminalApplication);
        }
        addAction(panel, ui, R.string.file_manager_set_wallpaper,
                DesktopUiFactory.COLOR_PANEL_ALT,
                DesktopWallpaperFileAction.supports(
                        target.mimeType, target.directory),
                dismiss, actions::setWallpaper);
        if (target.canCreateDesktopShortcut) {
            addAction(panel, ui, R.string.file_manager_create_desktop_shortcut,
                    DesktopUiFactory.COLOR_PANEL_ALT, true,
                    dismiss, actions::createDesktopShortcut);
        }
        addAction(panel, ui, R.string.file_manager_copy,
                DesktopUiFactory.COLOR_PANEL_ALT, true,
                dismiss, actions::copy);
        addAction(panel, ui, R.string.file_manager_cut,
                DesktopUiFactory.COLOR_PANEL_ALT, true,
                dismiss, actions::cut);
        addAction(panel, ui, R.string.action_rename,
                DesktopUiFactory.COLOR_PANEL_ALT, true,
                dismiss, actions::rename);
        addAction(panel, ui, R.string.action_delete,
                DesktopUiFactory.COLOR_RED, true,
                dismiss, actions::delete);
        addAction(panel, ui, R.string.file_manager_copy_path,
                DesktopUiFactory.COLOR_PANEL_ALT, true,
                dismiss, actions::copyPath);
        addAction(panel, ui, R.string.file_manager_properties,
                DesktopUiFactory.COLOR_PANEL_ALT, true,
                dismiss, actions::properties);
    }

    static void addAction(
            final LinearLayout panel,
            final DesktopUiFactory ui,
            final int textResId,
            final int color,
            final boolean enabled,
            final Runnable dismiss,
            final Runnable action) {
        final Button button = ui.menuItem(textResId, color);
        button.setEnabled(enabled);
        button.setOnClickListener(view -> {
            dismiss.run();
            action.run();
        });
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        ui.menuItemHeight());
        panel.addView(button, params);
    }
}
