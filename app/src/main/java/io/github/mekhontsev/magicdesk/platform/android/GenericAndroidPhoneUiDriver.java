package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import android.content.Context;

/** No-op phone UI integration for the Generic Android profile. */
final class GenericAndroidPhoneUiDriver implements PlatformPhoneUiDriver {
    @Override
    public boolean isPhoneScreenOff(final Context context) {
        return false;
    }

    @Override
    public boolean isPhoneScreenControlActive() {
        return false;
    }

    @Override
    public boolean setPhoneScreenOff(
            final boolean screenOff,
            final int desktopDisplayId) {
        return false;
    }

    @Override
    public void requestPhoneScreenRestore() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
