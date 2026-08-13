package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;

/** Generic Android has no wallpaper source beyond WallpaperManager. */
final class GenericAndroidWallpaperDriver implements PlatformWallpaperDriver {
    @Override
    public ParcelFileDescriptor openCurrentFallback() {
        return null;
    }
}
