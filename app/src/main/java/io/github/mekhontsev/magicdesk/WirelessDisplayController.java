package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/** Opens REDMAGIC's stock Miracast device picker. */
final class WirelessDisplayController {
    private static final String TAG = "MagicDeskWirelessDisplay";
    private static final ComponentName SMART_CAST_COMPONENT =
            new ComponentName(
                    "cn.nubia.touping",
                    "cn.nubia.touping.HomeActivity");

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
                    "Could not open the REDMAGIC wireless display picker",
                    error.getMessage(),
                    error);
            return false;
        }
    }
}
