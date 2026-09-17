package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/** Resolves Android icons or cached Termux artwork; never performs command/file I/O. */
final class DesktopApplicationIconResolver {
    private DesktopApplicationIconResolver() {
    }

    static Drawable resolve(
            final Context context,
            final DesktopApplicationShortcut shortcut) {
        if (shortcut.hasExecLaunch() && (shortcut.execBackend == DesktopExecBackend.X11
                || shortcut.execBackend == DesktopExecBackend.TERMUX)) {
            final var bitmap = ApplicationCatalog.cachedTermuxIcon(shortcut.icon);
            return bitmap == null ? context.getDrawable(R.drawable.ic_file_console)
                    : new android.graphics.drawable.BitmapDrawable(context.getResources(), bitmap);
        }
        final String packageName = shortcut.launchTarget != null
                ? shortcut.launchTarget.packageName : shortcut.icon;
        if (packageName != null && !packageName.isEmpty()) {
            try {
                return context.getPackageManager()
                        .getApplicationIcon(packageName);
            } catch (PackageManager.NameNotFoundException
                    | RuntimeException ignored) {
                // Freedesktop icon names do not map to Android packages.
            }
        }
        return context.getDrawable(R.drawable.ic_file_console);
    }
}
