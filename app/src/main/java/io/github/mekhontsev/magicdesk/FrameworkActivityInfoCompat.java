package io.github.mekhontsev.magicdesk;

import android.content.pm.ActivityInfo;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Release-dependent ActivityInfo metadata unavailable in the public SDK stubs. */
final class FrameworkActivityInfoCompat {
    private static final String TAG = "MagicDeskActivityInfo";
    private static boolean sResolved;
    private static Field sResizeMode;
    private static Method sSupportsPictureInPicture;

    private FrameworkActivityInfoCompat() {
    }

    static Integer resizeMode(final ActivityInfo activityInfo) {
        if (activityInfo == null) {
            return null;
        }
        resolve();
        if (sResizeMode == null) {
            return null;
        }
        try {
            return Integer.valueOf(sResizeMode.getInt(activityInfo));
        } catch (IllegalAccessException | RuntimeException error) {
            return null;
        }
    }

    static Boolean supportsPictureInPicture(
            final ActivityInfo activityInfo) {
        if (activityInfo == null) {
            return null;
        }
        resolve();
        if (sSupportsPictureInPicture == null) {
            return null;
        }
        try {
            return (Boolean) sSupportsPictureInPicture.invoke(activityInfo);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static synchronized void resolve() {
        if (sResolved) {
            return;
        }
        sResolved = true;
        try {
            sResizeMode = ActivityInfo.class.getDeclaredField("resizeMode");
            sResizeMode.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.i(TAG, "ActivityInfo.resizeMode is unavailable");
        }
        try {
            sSupportsPictureInPicture = ActivityInfo.class
                    .getDeclaredMethod("supportsPictureInPicture");
            sSupportsPictureInPicture.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.i(TAG, "ActivityInfo PiP metadata is unavailable");
        }
    }
}
