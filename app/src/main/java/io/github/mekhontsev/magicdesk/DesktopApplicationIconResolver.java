package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/** Resolves Android icons or cached Linux artwork; never performs command/file I/O. */
final class DesktopApplicationIconResolver {
    private DesktopApplicationIconResolver() {
    }

    static android.graphics.Bitmap cachedIcon(DesktopApplicationShortcut shortcut) {
        if (shortcut == null) return null;
        if (shortcut.icon.startsWith("guest:")) return ApplicationCatalog.cachedGuestIcon(shortcut.icon);
        return shortcut.hasExecLaunch() && shortcut.execBackend == DesktopExecBackend.TERMUX
                ? ApplicationCatalog.cachedTermuxIcon(shortcut.icon) : null;
    }

    static android.graphics.Bitmap hostedIcon(android.graphics.Bitmap client, RecentApplicationStore.Entry recipe) {
        return client != null ? client : recipe == null ? null : cachedIcon(recipe.shortcut());
    }

    static Drawable resolve(
            final Context context,
            final DesktopApplicationShortcut shortcut) {
        if (shortcut.icon.startsWith("guest:") || shortcut.hasExecLaunch() && shortcut.execBackend == DesktopExecBackend.TERMUX) {
            final var bitmap = cachedIcon(shortcut);
            return bitmap == null ? UiAppearance.symbol(context, R.drawable.ic_file_console, UiColor.TEXT)
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
        return UiAppearance.symbol(context, R.drawable.ic_file_console, UiColor.TEXT);
    }
}
