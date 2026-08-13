package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

final class GenericAndroidPointerDriver implements PlatformPointerDriver {
    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean capturePosition() {
        return false;
    }

    @Override
    public Point restorePositionIfDisplaced() {
        return null;
    }

    @Override
    public int[] getPosition(final int displayId) {
        throw new IllegalStateException(
                "absolute pointer control is unavailable on this platform");
    }

    @Override
    public boolean injectClick(final int displayId, final int button) {
        return false;
    }

    @Override
    public boolean updatePosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        return false;
    }

    @Override
    public void refreshViewport() {
    }

    @Override
    public void close() {
    }
}
