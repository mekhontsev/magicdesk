package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.IOException;

/** Opens RedMagic's stock Miracast device picker. */
final class WirelessDisplayController {
    private static final String TAG = "MagicDeskWirelessDisplay";
    private static final ComponentName SMART_CAST_COMPONENT =
            new ComponentName(
                    "cn.nubia.touping",
                    "cn.nubia.touping.HomeActivity");
    private static final String DISCONNECT_COMMAND =
            "io.github.mekhontsev.magicdesk.WirelessDisplayCommand";

    private WirelessDisplayController() {
    }

    static boolean isAvailable(final Context context) {
        if (context == null) {
            return false;
        }
        try {
            context.getPackageManager().getActivityInfo(
                    SMART_CAST_COMPONENT, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    static boolean openPicker(final Activity activity) {
        if (activity == null || !isAvailable(activity)) {
            return false;
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(SMART_CAST_COMPONENT));
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not open SmartCast", error);
            CompatibilityDiagnostics.record(
                    "WIRELESS-DISPLAY-001",
                    "Could not open the RedMagic wireless display picker",
                    error.getMessage(),
                    error);
            return false;
        }
    }

    static boolean disconnect() throws IOException {
        final String output = ShellAccess.run(
                AppProcessCommand.run(DISCONNECT_COMMAND)).trim();
        if (output.contains("wireless-display-disconnected")) {
            return true;
        }
        Log.w(TAG, "Could not disconnect wireless display output=" + output);
        return false;
    }
}
