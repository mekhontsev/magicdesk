package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

/** Optional absolute-pointer API supplied by a firmware platform. */
public interface PlatformPointerDriver extends AutoCloseable {
    boolean isAvailable();

    /** Whether this vendor pointer backend owns the requested display. */
    default boolean supportsDisplay(final int displayId) {
        return isAvailable();
    }

    boolean capturePosition();

    Point restorePositionIfDisplaced();

    int[] getPosition(int displayId);

    boolean injectClick(int displayId, int button);

    boolean updatePosition(
            int displayId,
            int x,
            int y,
            int action,
            long downTime);

    void refreshViewport();

    @Override
    void close();
}
