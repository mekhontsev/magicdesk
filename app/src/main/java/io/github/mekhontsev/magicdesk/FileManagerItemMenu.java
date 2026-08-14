package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

final class FileManagerItemMenu {
    interface Listener {
        void onItemOpen(ShellFileInfo file);
        void onItemCopy(ShellFileInfo file);
        void onItemCut(ShellFileInfo file);
        void onItemRename(ShellFileInfo file);
        void onItemDelete(ShellFileInfo file);
        void onItemProperties(ShellFileInfo file);
        void onItemOpenWith(ShellFileInfo file);
        void onItemCopyPath(ShellFileInfo file);
        void onItemInstall(ShellFileInfo file);
        void onItemRunScript(ShellFileInfo file);
        void onItemSetWallpaper(ShellFileInfo file);
    }

    private static final int OPEN = 1;
    private static final int OPEN_WITH = 2;
    private static final int COPY = 3;
    private static final int CUT = 4;
    private static final int RENAME = 5;
    private static final int DELETE = 6;
    private static final int PROPERTIES = 7;
    private static final int COPY_PATH = 8;
    private static final int INSTALL = 9;
    private static final int RUN_SCRIPT = 10;
    private static final int SET_WALLPAPER = 11;

    private FileManagerItemMenu() {
    }

    static void show(
            final Activity activity,
            final View anchor,
            final ShellFileInfo file,
            final Listener listener) {
        final PopupMenu popup = new PopupMenu(activity, anchor);
        final Menu menu = popup.getMenu();
        menu.add(Menu.NONE, OPEN, 0, R.string.action_open);
        if (!file.directory) {
            menu.add(Menu.NONE, OPEN_WITH, 1,
                    R.string.file_manager_open_with);
        }
        if (ShellPackageInstaller.supports(file)) {
            menu.add(Menu.NONE, INSTALL, 2,
                    R.string.file_manager_install_apk);
        }
        if (ShellScriptLauncher.supports(file)) {
            menu.add(Menu.NONE, RUN_SCRIPT, 3,
                    R.string.file_manager_run_script);
        }
        if (DesktopWallpaperFileAction.supports(file)) {
            menu.add(Menu.NONE, SET_WALLPAPER, 4,
                    R.string.file_manager_set_wallpaper);
        }
        menu.add(Menu.NONE, COPY, 5, R.string.file_manager_copy);
        menu.add(Menu.NONE, CUT, 6, R.string.file_manager_cut);
        menu.add(Menu.NONE, RENAME, 7, R.string.action_rename);
        menu.add(Menu.NONE, DELETE, 8, R.string.action_delete);
        menu.add(Menu.NONE, COPY_PATH, 9,
                R.string.file_manager_copy_path);
        menu.add(Menu.NONE, PROPERTIES, 10,
                R.string.file_manager_properties);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case OPEN:
                    listener.onItemOpen(file);
                    return true;
                case OPEN_WITH:
                    listener.onItemOpenWith(file);
                    return true;
                case COPY:
                    listener.onItemCopy(file);
                    return true;
                case CUT:
                    listener.onItemCut(file);
                    return true;
                case RENAME:
                    listener.onItemRename(file);
                    return true;
                case DELETE:
                    listener.onItemDelete(file);
                    return true;
                case COPY_PATH:
                    listener.onItemCopyPath(file);
                    return true;
                case PROPERTIES:
                    listener.onItemProperties(file);
                    return true;
                case INSTALL:
                    listener.onItemInstall(file);
                    return true;
                case RUN_SCRIPT:
                    listener.onItemRunScript(file);
                    return true;
                case SET_WALLPAPER:
                    listener.onItemSetWallpaper(file);
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }
}
