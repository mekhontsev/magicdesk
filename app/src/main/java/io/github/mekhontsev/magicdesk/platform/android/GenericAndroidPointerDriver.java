package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformPointerDriver;

final class GenericAndroidPointerDriver implements PlatformPointerDriver {
    @Override
    public boolean isAvailable() {
        return false;
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
