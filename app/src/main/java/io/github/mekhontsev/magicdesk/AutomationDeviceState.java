package io.github.mekhontsev.magicdesk;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** On-demand readiness shared by automation and the interactive test entry point. */
final class AutomationDeviceState {
    private static final String INSTANCE_ID = UUID.randomUUID().toString();
    final Boolean interactive;
    final Boolean deviceLocked;
    final Boolean keyguardLocked;
    final int sdk;

    AutomationDeviceState(final Boolean interactive, final Boolean deviceLocked,
            final Boolean keyguardLocked, final int sdk) {
        this.interactive = interactive;
        this.deviceLocked = deviceLocked;
        this.keyguardLocked = keyguardLocked;
        this.sdk = sdk;
    }

    static AutomationDeviceState capture(final Context context) {
        final PowerManager power = context == null ? null
                : context.getSystemService(PowerManager.class);
        final KeyguardManager keyguard = context == null ? null
                : context.getSystemService(KeyguardManager.class);
        return new AutomationDeviceState(power == null ? null : power.isInteractive(),
                keyguard == null ? null : keyguard.isDeviceLocked(),
                keyguard == null ? null : keyguard.isKeyguardLocked(), Build.VERSION.SDK_INT);
    }

    String phoneUiUnavailableReason() {
        if (interactive == null || deviceLocked == null || keyguardLocked == null) {
            return "phone readiness is unavailable";
        }
        if (!interactive) {
            return "wake and unlock the phone before starting the test";
        }
        return deviceLocked || keyguardLocked
                ? "unlock the phone before starting the test" : null;
    }

    String selfTestUnavailableReason() {
        return RuntimeCapabilities.supportsDesktop(sdk) ? phoneUiUnavailableReason()
                : "Desktop self-tests require Android 15 or newer";
    }

    JSONObject toJson(final boolean shellReady) throws JSONException {
        final JSONArray actions = new JSONArray();
        if (Boolean.FALSE.equals(interactive)) actions.put("wake_device");
        if (Boolean.TRUE.equals(deviceLocked) || Boolean.TRUE.equals(keyguardLocked)) {
            actions.put("unlock_device");
        }
        if (interactive == null || deviceLocked == null || keyguardLocked == null) {
            actions.put("check_device_state");
        }
        if (!shellReady) actions.put("check_privileged_service");
        return new JSONObject().put("interactive", nullable(interactive))
                .put("deviceLocked", nullable(deviceLocked))
                .put("keyguardLocked", nullable(keyguardLocked))
                .put("selfTestReady", selfTestUnavailableReason() == null && shellReady)
                .put("selfTestUnavailableReason", selfTestUnavailableReason() == null
                        ? JSONObject.NULL : selfTestUnavailableReason())
                .put("requiredActions", actions);
    }

    static JSONObject deviceJson() throws JSONException {
        return new JSONObject().put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE);
    }

    static JSONObject appJson(final Context context) throws JSONException {
        final JSONObject app = new JSONObject().put("package", BuildConfig.APPLICATION_ID)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("buildId", BuildConfig.SOURCE_ID).put("instanceId", INSTANCE_ID)
                .put("lastUpdateTime", JSONObject.NULL);
        if (context != null) {
            try {
                app.put("lastUpdateTime", context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return app;
    }

    private static Object nullable(final Boolean value) {
        return value == null ? JSONObject.NULL : value;
    }
}
