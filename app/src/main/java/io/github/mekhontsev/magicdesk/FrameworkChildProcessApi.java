package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

/** Android's property/default policy, without acquiring a settings provider as the app. */
final class FrameworkChildProcessApi {
    static final String KEY = "settings_enable_monitor_phantom_procs";
    private final Method mIsEnabled;

    FrameworkChildProcessApi() throws ReflectiveOperationException, IOException {
        final Class<?> flags = Class.forName("android.util.FeatureFlagUtils");
        final Object defaults = flags.getMethod("getAllFeatureFlags").invoke(null);
        if (!(defaults instanceof Map<?, ?> values) || !values.containsKey(KEY)) {
            throw new IOException("Android child process monitoring flag is unavailable");
        }
        mIsEnabled = flags.getMethod("isEnabled", Context.class, String.class);
    }

    boolean readDefault() throws ReflectiveOperationException, IOException {
        final Object value = mIsEnabled.invoke(null, null, KEY);
        if (!(value instanceof Boolean enabled)) {
            throw new IOException("Android child process monitoring state is unavailable");
        }
        return enabled;
    }

}
