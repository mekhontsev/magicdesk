package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import android.content.Context;

/** RedMagic phone UI integration used around external desktop sessions. */
final class NubiaPhoneUiDriver implements PlatformPhoneUiDriver {
    private static final String[] OBSERVED_SETTINGS = {
            NubiaPhoneScreenState.SETTING
    };

    @Override
    public boolean requiresPhoneImeRouting() {
        return true;
    }

    @Override
    public boolean requiresLauncherOwnedOverview() {
        // Nubia's fallback Quickstep crashes while binding desktop task groups
        // under a third-party HOME. MagicDesk owns the phone Overview for the
        // same lease instead of starting that incompatible fallback surface.
        return true;
    }

    @Override
    public boolean isPhoneScreenOff(final Context context) {
        return NubiaPhoneScreenState.isOff();
    }

    @Override
    public boolean isPhoneScreenControlActive() {
        return PhoneDisplayGuard.isActive();
    }

    @Override
    public boolean setPhoneScreenOff(
            final boolean screenOff,
            final int desktopDisplayId) {
        return screenOff
                ? PhoneDisplayGuard.enable(desktopDisplayId)
                : PhoneDisplayGuard.disable();
    }

    @Override
    public void requestPhoneScreenRestore() {
        PhoneDisplayGuard.requestRestore();
    }

    @Override
    public String[] observedSettingKeys() {
        return OBSERVED_SETTINGS.clone();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
