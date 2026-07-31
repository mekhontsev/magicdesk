package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

final class ConsoleModeState {
    static final String DISPLAY_ID_SETTING = "app_mirror_displayid";
    static final String PHONE_SCREEN_OFF_SETTING = "nubia_screen_off_tp";

    private static final String TAG = "MagicDeskConsoleState";
    private static volatile boolean sShizukuPhoneScreenOff;

    private ConsoleModeState() {
    }

    static int activeDisplayId(final Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(),
                    DISPLAY_ID_SETTING,
                    -1);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot read Console Mode display", error);
            return -1;
        }
    }

    static boolean isActive(final Context context) {
        return activeDisplayId(context) > 0;
    }

    static boolean isPhoneScreenOff(final Context context) {
        if (RuntimeAccess.allowsShizukuCommands()
                && !RuntimeAccess.allowsRootCommands()) {
            return sShizukuPhoneScreenOff;
        }
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(),
                    PHONE_SCREEN_OFF_SETTING,
                    0) == 1;
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot read phone screen state", error);
            return false;
        }
    }

    static boolean setShizukuPhoneScreenOff(final boolean screenOff) {
        if (sShizukuPhoneScreenOff == screenOff) {
            return false;
        }
        sShizukuPhoneScreenOff = screenOff;
        return true;
    }
}
