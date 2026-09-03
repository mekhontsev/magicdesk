package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Immutable state exported by the live desktop host to automation clients. */
final class DesktopUiSnapshot {
    static final DesktopUiSnapshot UNAVAILABLE = new DesktopUiSnapshot(
            false, -1, false, null, false, false, null, "",
            false, false);

    final boolean available;
    final int displayId;
    final boolean taskbarVisible;
    final Rect taskbarBounds;
    final boolean startVisible;
    final boolean popupVisible;
    final Rect popupBounds;
    final String popupTitle;
    final boolean wallpaperRendered;
    final boolean fallbackWallpaper;

    DesktopUiSnapshot(
            final boolean available,
            final int displayId,
            final boolean taskbarVisible,
            final Rect taskbarBounds,
            final boolean startVisible,
            final boolean popupVisible,
            final Rect popupBounds,
            final String popupTitle,
            final boolean wallpaperRendered,
            final boolean fallbackWallpaper) {
        this.available = available;
        this.displayId = displayId;
        this.taskbarVisible = taskbarVisible;
        this.taskbarBounds = copy(taskbarBounds);
        this.startVisible = startVisible;
        this.popupVisible = popupVisible;
        this.popupBounds = copy(popupBounds);
        this.popupTitle = popupTitle == null ? "" : popupTitle;
        this.wallpaperRendered = wallpaperRendered;
        this.fallbackWallpaper = fallbackWallpaper;
    }

    private static Rect copy(final Rect value) {
        return value == null ? new Rect() : new Rect(value);
    }
}
