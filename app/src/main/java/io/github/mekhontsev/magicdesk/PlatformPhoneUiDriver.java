package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Firmware-specific phone UI behavior while a desktop session is active. */
public interface PlatformPhoneUiDriver {
    boolean isAvailable();

    boolean requiresPhoneImeRouting();

    /** Whether firmware Recents requests must open the active MagicDesk HOME. */
    boolean requiresRecentsRedirectToHome();

    boolean isPhoneScreenOff(Context context);

    boolean isPhoneScreenControlActive();

    boolean setPhoneScreenOff(boolean screenOff, int desktopDisplayId);

    void requestPhoneScreenRestore();

    String[] observedSettingKeys();
}
