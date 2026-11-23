package io.github.mekhontsev.magicdesk.platform.honor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

/** Exclude a managed task from the firmware's count without taking over its window. */
final class HonorFreeformMultiplicity implements PlatformWindowingDriver.FreeformMultiplicity {
    private final Field extension, customized, takeover, profile;
    private final Method active, limited, recents, isLimited;
    private final Method wctExtension, setWindow;
    private final Constructor<?> property;

    static HonorFreeformMultiplicity detect(Field extension, Method accessor, Class<?> wct, Class<?> token) {
        if (accessor == null) return null;
        try {
            return new HonorFreeformMultiplicity(extension, Class.forName("android.window.HnCustomizedWindowProp",
                    false, wct.getClassLoader()), accessor, wct, token);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    HonorFreeformMultiplicity(Field extension, Class<?> prop, Method accessor, Class<?> wct, Class<?> token)
            throws ReflectiveOperationException {
        this.extension = extension;
        customized = extension.getType().getField("mIsCustomizedFreeformWindow");
        takeover = extension.getType().getField("mIsTakeoverWindow");
        profile = extension.getType().getField("mCustomizedWindowProp");
        property = prop.getConstructor();
        active = prop.getMethod("setActiveState", boolean.class);
        limited = prop.getMethod("setIsLimitedByQuantity", boolean.class);
        isLimited = prop.getMethod("isLimitedByQuantity");
        recents = prop.getMethod("setFocusableInRecent", boolean.class);
        wctExtension = accessor;
        setWindow = accessor.getReturnType().getMethod("setCustomizedWindow", wct, token, prop);
    }

    @Override public boolean canClaim(Object task) throws IllegalAccessException {
        Object ex = extension.get(task);
        return ex != null && !customized.getBoolean(ex) && !takeover.getBoolean(ex);
    }

    @Override public String describe(Object task) {
        try {
            Object ex = extension.get(task);
            if (ex == null) return "unknown";
            if (takeover.getBoolean(ex)) return "takeover";
            if (!customized.getBoolean(ex)) return "ordinary";
            Object prop = profile.get(ex);
            return prop == null ? "unknown" : (Boolean) isLimited.invoke(prop)
                    ? "custom-limited" : "custom-exempt";
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return "unknown";
        }
    }

    @Override public void setExempt(Object transaction, Object token,
            boolean exempt) throws ReflectiveOperationException {
        Object prop = property.newInstance();
        active.invoke(prop, exempt);
        limited.invoke(prop, !exempt);
        // Ordinary tasks remain selectable in Recents; the custom profile defaults to false.
        recents.invoke(prop, true);
        setWindow.invoke(wctExtension.invoke(transaction), transaction, token, prop);
    }
}
