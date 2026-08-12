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
    public void restorePositionIfDisplaced() {
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
    public Point restoreKnownPosition(final int displayId) {
        return null;
    }

    @Override
    public void close() {
    }
}
