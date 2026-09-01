package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Firmware-specific phone UI behavior while a desktop session is active. */
public interface PlatformPhoneUiDriver {
    boolean isAvailable();

    boolean requiresPhoneImeRouting();

    /** Whether the active MagicDesk HOME must replace the firmware Overview. */
    boolean requiresLauncherOwnedOverview();

    boolean isPhoneScreenOff(Context context);

    boolean isPhoneScreenControlActive();

    boolean setPhoneScreenOff(boolean screenOff, int desktopDisplayId);

    void requestPhoneScreenRestore();

    String[] observedSettingKeys();
}
