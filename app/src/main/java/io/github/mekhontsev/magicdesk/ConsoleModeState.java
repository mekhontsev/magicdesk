package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;

final class ConsoleModeState {
    static final String DISPLAY_ID_SETTING = "app_mirror_displayid";
    static final String PHONE_SCREEN_OFF_SETTING = "nubia_screen_off_tp";

    private static final String TAG = "MagicDeskConsoleState";
    private static final long SHELL_READ_TIMEOUT_MILLIS = 2_000L;
    private static final int SHELL_READ_OUTPUT_LIMIT_BYTES = 4 * 1024;
    private static volatile boolean sPhoneScreenOff;

    private ConsoleModeState() {
    }

    static int activeDisplayId(final Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(),
                    DISPLAY_ID_SETTING,
                    -1);
        } catch (RuntimeException error) {
            // A Shizuku UserService runs as shell, so its Context package does
            // not match the Binder caller UID accepted by SettingsProvider.
            final int shellValue = readActiveDisplayIdAsShell();
            if (shellValue >= 0) {
                return shellValue;
            }
            Log.w(TAG, "Cannot read Console Mode display", error);
            return -1;
        }
    }

    private static int readActiveDisplayIdAsShell() {
        try {
            final Process process = new ProcessBuilder(
                    "/system/bin/settings", "get", "global",
                    DISPLAY_ID_SETTING)
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(
                            process,
                            SHELL_READ_TIMEOUT_MILLIS,
                            SHELL_READ_OUTPUT_LIMIT_BYTES);
            if (result.exitCode != 0 || result.truncated) {
                return -1;
            }
            return Integer.parseInt(result.output.trim());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (IOException | NumberFormatException ignored) {
            return -1;
        }
    }

    static boolean isActive(final Context context) {
        return activeDisplayId(context) > 0;
    }

    static boolean isPhoneScreenOff(final Context context) {
        return sPhoneScreenOff;
    }

    static boolean setPhoneScreenOff(final boolean screenOff) {
        if (sPhoneScreenOff == screenOff) {
            return false;
        }
        sPhoneScreenOff = screenOff;
        return true;
    }
}
