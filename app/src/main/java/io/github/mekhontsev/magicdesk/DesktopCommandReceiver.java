package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.lang.reflect.Method;

public final class DesktopCommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null
                || !MainActivity.BROADCAST_SHOW_START.equals(intent.getAction())) {
            return;
        }
        if (!DesktopRuntimeBridge.showStart()) {
            final ActivityOptions options = ActivityOptions.makeBasic();
            final int displayId = Settings.Global.getInt(
                    context.getContentResolver(), "app_mirror_displayid", -1);
            if (displayId > 0) {
                options.setLaunchDisplayId(displayId);
                setIntOption(options, "setLaunchActivityType", 2);
            }
            setIntOption(options, "setLaunchWindowingMode", 1);
            context.startActivity(MainActivity.createShowStartIntent(context),
                    options.toBundle());
        }
    }

    private static void setIntOption(final ActivityOptions options,
            final String methodName, final int value) {
        try {
            final Method method = ActivityOptions.class.getMethod(
                    methodName, Integer.TYPE);
            method.invoke(options, Integer.valueOf(value));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The root Console Mode entry point also requests fullscreen explicitly.
        }
    }
}
