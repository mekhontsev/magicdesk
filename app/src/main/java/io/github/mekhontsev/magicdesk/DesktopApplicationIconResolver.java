package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/** Resolves Android package icons and falls back for command applications. */
final class DesktopApplicationIconResolver {
    private DesktopApplicationIconResolver() {
    }

    static Drawable resolve(
            final Context context,
            final DesktopApplicationShortcut shortcut) {
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
