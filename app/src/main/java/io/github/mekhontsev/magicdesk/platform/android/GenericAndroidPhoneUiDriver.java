package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;
import io.github.mekhontsev.magicdesk.PhoneDisplayGuard;
import android.content.Context;

/** Shared Android panel power with optional platform background-work protection. */
final class GenericAndroidPhoneUiDriver implements PlatformPhoneUiDriver {
    @Override
    public boolean isPhoneScreenOff(final Context context) {
        return PhoneDisplayGuard.isScreenOff();
    }

    @Override
    public boolean isPhoneScreenControlActive() {
        return PhoneDisplayGuard.isActive();
    }

    @Override
    public void requestPhoneScreenRestore() {
        PhoneDisplayGuard.requestRestore();
    }

    @Override public boolean restorePhoneScreenForDisplay(int displayId) {
        return PhoneDisplayGuard.restoreForDisplay(displayId);
    }

    @Override
    public boolean isAvailable() {
        return PhoneDisplayGuard.isAvailable();
    }
}
