package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import android.content.Context;

/** No-op phone UI integration for the Generic Android profile. */
final class GenericAndroidPhoneUiDriver implements PlatformPhoneUiDriver {
    private static final String[] NO_SETTINGS = new String[0];

    @Override
    public boolean requiresPhoneImeRouting() {
        return false;
    }

    @Override
    public boolean requiresLauncherOwnedOverview() {
        return false;
    }

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
    public String[] observedSettingKeys() {
        return NO_SETTINGS;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
