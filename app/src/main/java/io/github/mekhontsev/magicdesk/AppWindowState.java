package io.github.mekhontsev.magicdesk;

import java.util.Objects;

final class AppWindowState {
    enum Mode {
        WINDOWED,
        FULLSCREEN
    }

    final Mode mode;
    final RelativeWindowBounds windowBounds;

    AppWindowState(
            final Mode mode,
            final RelativeWindowBounds windowBounds) {
        this.mode = mode;
        this.windowBounds = windowBounds;
    }

    AppWindowState withMode(final Mode newMode) {
        return new AppWindowState(newMode, windowBounds);
    }

    AppWindowState withWindowBounds(
            final RelativeWindowBounds newBounds) {
        return new AppWindowState(mode, newBounds);
    }

    boolean shouldLaunchWindowed() {
        return mode == Mode.WINDOWED
                || (mode == null && windowBounds != null);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AppWindowState)) {
            return false;
        }
        final AppWindowState state = (AppWindowState) other;
        return mode == state.mode
                && Objects.equals(windowBounds, state.windowBounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, windowBounds);
    }
}
