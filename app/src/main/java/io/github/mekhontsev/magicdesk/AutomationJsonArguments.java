package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;

import java.math.BigDecimal;

/** Exact numeric arguments shared by the automation adapters. */
final class AutomationJsonArguments {
    private AutomationJsonArguments() {
    }

    static AppIdentity requiredApplication(
            final android.content.Context context, final JSONObject arguments) {
        final AppIdentity application = AppIdentity.fromPersistentKey(
                arguments == null ? null : arguments.optString("appIdentity", null));
        AppProfile.requireCurrent(context, application);
        return application;
    }

    static AppLaunchTarget applicationTarget(
            final AppIdentity application, final JSONObject arguments) {
        final String componentValue = arguments.optString("component", "").trim();
        if (componentValue.isEmpty()) {
            return AppLaunchTarget.packageDefault(application.packageName);
        }
        final android.content.ComponentName component =
                android.content.ComponentName.unflattenFromString(componentValue);
        if (component == null || !application.packageName.equals(component.getPackageName())) {
            throw new IllegalArgumentException("component must belong to application");
        }
        return AppLaunchTarget.explicit(application.packageName, component.getClassName(),
                android.content.Intent.ACTION_MAIN);
    }

    static int requiredInt(final JSONObject object, final String key) {
        final long value = requiredLong(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        return (int) value;
    }

    static long requiredLong(final JSONObject object, final String key) {
        if (object == null || !object.has(key)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return longValue(object.opt(key), key);
    }

    static long longValue(final Object value, final String name) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException(
                    name + " must be an integer in the signed 64-bit range", error);
        }
    }
}
