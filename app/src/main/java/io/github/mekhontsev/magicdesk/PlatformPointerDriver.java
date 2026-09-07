package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

/** Optional absolute-pointer API supplied by a firmware platform. */
public interface PlatformPointerDriver extends AutoCloseable {
    boolean isAvailable();

    /** Whether this vendor pointer backend owns the requested display. */
    default boolean supportsDisplay(final int displayId) {
        return isAvailable();
    }

    /** Firmware converts native secondary-button events instead of delivering them. */
    default boolean requiresSecondaryClickInjection() {
        return false;
    }

    /** Current system cursor position, or {@code null} when not observable. */
    default Point observePosition(final int displayId) {
        return null;
    }

    boolean injectClick(int displayId, int button);

    boolean updatePosition(
            int displayId,
            int x,
            int y,
            int action,
            long downTime);

    @Override
    void close();
}
