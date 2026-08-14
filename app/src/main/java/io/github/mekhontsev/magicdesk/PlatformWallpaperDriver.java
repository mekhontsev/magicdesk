package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;

/** Optional platform source used when Android has no static wallpaper file. */
public interface PlatformWallpaperDriver {
    ParcelFileDescriptor openCurrentFallback();
}
