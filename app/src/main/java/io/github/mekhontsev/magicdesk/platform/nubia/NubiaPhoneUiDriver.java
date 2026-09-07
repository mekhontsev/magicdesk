package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import android.content.Context;

/** RedMagic phone UI integration used around external desktop sessions. */
final class NubiaPhoneUiDriver implements PlatformPhoneUiDriver {
    @Override
    public boolean requiresRecentsRedirectToHome() {
        // Nubia's fallback Quickstep crashes while binding desktop task groups
        // under a third-party HOME. Redirect Recents to that HOME for the same
        // lease instead of starting the incompatible fallback surface.
        return true;
    }

    @Override
    public boolean isPhoneScreenOff(final Context context) {
        return PhoneDisplayGuard.isScreenOff();
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
    public boolean isAvailable() {
        return true;
    }
}
