package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Android 15+ activity input policy used by shell-owned UI tasks. */
final class FrameworkActivityInputApi {
    private FrameworkActivityInputApi() {
    }

    static IBinder requireActivityToken(final Activity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity is required");
        }
        try {
            final Object token = Activity.class
                    .getMethod("getActivityToken")
                    .invoke(activity);
            if (token instanceof IBinder) {
                return (IBinder) token;
            }
            throw new IllegalStateException("activity token is unavailable");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "Android activity token API is unavailable", error);
        }
    }

    static void setRecordInputSinkEnabled(
            final IBinder activityToken,
            final boolean enabled) {
        if (activityToken == null) {
            throw new IllegalArgumentException("activity token is required");
        }
        try {
            final Class<?> activityClientClass = Class.forName(
                    "android.app.ActivityClient");
            final Object activityClient = activityClientClass
                    .getMethod("getInstance")
                    .invoke(null);
            final Method method = activityClientClass.getDeclaredMethod(
                    "setActivityRecordInputSinkEnabled",
                    IBinder.class,
                    Boolean.TYPE);
            method.setAccessible(true);
            method.invoke(
                    activityClient,
                    activityToken,
                    Boolean.valueOf(enabled));
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            throw new IllegalStateException(
                    "cannot update Android activity input policy",
                    cause == null ? error : cause);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "Android activity input policy API is unavailable",
                    error);
        }
    }
}
