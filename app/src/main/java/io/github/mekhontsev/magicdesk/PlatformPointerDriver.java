package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

/** Optional absolute-pointer API supplied by a firmware platform. */
interface PlatformPointerDriver extends AutoCloseable {
    boolean isAvailable();

    boolean capturePosition();

    void restorePositionIfDisplaced();

    int[] getPosition(int displayId);

    boolean injectClick(int displayId, int button);

    boolean updatePosition(
            int displayId,
            int x,
            int y,
            int action,
            long downTime);

    void refreshViewport();

    Point restoreKnownPosition(int displayId)
            throws ReflectiveOperationException;

    @Override
    void close();
}
