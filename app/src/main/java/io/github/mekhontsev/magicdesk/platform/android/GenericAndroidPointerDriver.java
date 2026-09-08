package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformPointerDriver;

final class GenericAndroidPointerDriver implements PlatformPointerDriver {
    @Override
    public boolean isAvailable() {
        return false;
    }
}
