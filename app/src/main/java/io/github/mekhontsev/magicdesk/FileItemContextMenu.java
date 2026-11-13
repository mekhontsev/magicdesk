package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Shared file/folder context menu for the desktop and Files. */
final class FileItemContextMenu {
    interface Actions {
        void open();
        void openWith();
        void share();
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
        final PopupWindow[] popupHolder = new PopupWindow[1];
        populate(activity, ui, panel, target, actions, () -> {
            if (popupHolder[0] != null) {
                popupHolder[0].dismiss();
            }
        });
        final PopupWindow popup = ui.showMenuPopup(anchor, panel);
        popupHolder[0] = popup;
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
                UiColor.ACCENT, true, dismiss, actions::open);
        addAction(panel, ui, R.string.file_manager_open_with,
                UiColor.SURFACE, !target.directory,
                dismiss, actions::openWith);
        addAction(panel, ui, R.string.file_manager_share,
                UiColor.SURFACE, !target.directory,
                dismiss, actions::share);
        addAction(panel, ui, R.string.file_manager_install_apk,
                UiColor.SURFACE,
                ShellPackageInstaller.supports(
                        target.name, target.mimeType, target.directory),
                dismiss, actions::install);
        addAction(panel, ui, R.string.file_manager_run_script,
                UiColor.SURFACE,
                ShellScriptLauncher.supports(
                        target.name, target.mimeType, target.directory),
                dismiss, actions::runScript);
        if (target.canCreateTerminalApplication) {
            addAction(panel, ui, R.string.action_add_as_terminal_application,
                    UiColor.SURFACE, true,
                    dismiss, actions::createTerminalApplication);
        }
        addAction(panel, ui, R.string.file_manager_set_wallpaper,
                UiColor.SURFACE,
                DesktopWallpaperFileAction.supports(
                        target.mimeType, target.directory),
                dismiss, actions::setWallpaper);
        if (target.canCreateDesktopShortcut) {
            addAction(panel, ui, R.string.file_manager_create_desktop_shortcut,
                    UiColor.SURFACE, true,
                    dismiss, actions::createDesktopShortcut);
        }
        addAction(panel, ui, R.string.file_manager_copy,
                UiColor.SURFACE, true,
                dismiss, actions::copy);
        addAction(panel, ui, R.string.file_manager_cut,
                UiColor.SURFACE, true,
                dismiss, actions::cut);
        addAction(panel, ui, R.string.action_rename,
                UiColor.SURFACE, true,
                dismiss, actions::rename);
        addAction(panel, ui, R.string.action_delete,
                UiColor.DANGER, true,
                dismiss, actions::delete);
        addAction(panel, ui, R.string.file_manager_copy_path,
                UiColor.SURFACE, true,
                dismiss, actions::copyPath);
        addAction(panel, ui, R.string.file_manager_properties,
                UiColor.SURFACE, true,
                dismiss, actions::properties);
    }

    static void addAction(
            final LinearLayout panel,
            final DesktopUiFactory ui,
            final int textResId,
            final UiColor color,
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
