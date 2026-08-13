package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;

/** Reads the active ZTE/nubia theme wallpaper when WallpaperManager has no file. */
final class NubiaWallpaperDriver implements PlatformWallpaperDriver {
    private static final File THEME_CACHE =
            new File("/data/resource-cache/cache");
    private static final String[] WALLPAPER_NAMES = {
            "wallpaper1.jpg",
            "wallpaper1.png"
    };

    @Override
    public ParcelFileDescriptor openCurrentFallback() {
        final String theme = currentThemeName();
        if (!isSafeThemeName(theme)) {
            return null;
        }
        final File wallpaperDirectory = new File(
                new File(THEME_CACHE, theme), "wallpaper");
        for (final String name : WALLPAPER_NAMES) {
            final File wallpaper = new File(wallpaperDirectory, name);
            if (!wallpaper.isFile()) {
                continue;
            }
            try {
                return ParcelFileDescriptor.open(
                        wallpaper, ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (FileNotFoundException ignored) {
                // The active theme may be changing while the desktop starts.
            }
        }
        return null;
    }

    private static String currentThemeName() {
        String theme = normalizeThemeName(readProperty(
                "persist.sys.theme_name"));
        if (!theme.isEmpty()) {
            return theme;
        }
        theme = normalizeThemeName(readProperty(
                "ro.vendor.build.def_theme_name"));
        if (!theme.isEmpty()) {
            return theme;
        }
        return normalizeThemeName(readProperty("ro.build.def_theme_name"));
    }

    static String normalizeThemeName(final String value) {
        if (value == null) {
            return "";
        }
        final String trimmed = value.trim();
        final int separator = trimmed.indexOf(';');
        return (separator >= 0 ? trimmed.substring(0, separator) : trimmed)
                .trim();
    }

    static boolean isSafeThemeName(final String theme) {
        if (theme == null || theme.isEmpty() || theme.contains("..")) {
            return false;
        }
        for (int index = 0; index < theme.length(); index++) {
            final char character = theme.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && character != '_'
                    && character != '-'
                    && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static String readProperty(final String name) {
        try {
            final Class<?> systemProperties = Class.forName(
                    "android.os.SystemProperties");
            final Method get = systemProperties.getMethod(
                    "get", String.class, String.class);
            return (String) get.invoke(null, name, "");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }
}
