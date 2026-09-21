package io.github.mekhontsev.magicdesk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** DisplayManager's linear brightness domain, independent of vendor slider ranges. */
final class FrameworkDisplayBrightnessApi {
    private final Object mManager;
    private final Method mRead;
    private final Method mWrite;
    private final Field mBrightness;

    FrameworkDisplayBrightnessApi() throws ReflectiveOperationException {
        final Class<?> manager = Class.forName("android.hardware.display.DisplayManagerGlobal");
        mManager = manager.getMethod("getInstance").invoke(null);
        mRead = manager.getMethod("getBrightnessInfo", int.class);
        mWrite = manager.getMethod("setBrightness", int.class, float.class);
        mBrightness = Class.forName("android.hardware.display.BrightnessInfo").getField("brightness");
    }

    void preserveCurrentBrightness(final int displayId) throws ReflectiveOperationException {
        final Object info = mRead.invoke(mManager, displayId);
        if (info == null) throw new IllegalStateException("display brightness is unavailable");
        final float brightness = mBrightness.getFloat(info);
        if (!Float.isFinite(brightness) || brightness < 0 || brightness > 1) {
            throw new IllegalStateException("display brightness is outside the supported range");
        }
        // Populate the manual setting before changing mode; never convert to a 0..255 slider.
        mWrite.invoke(mManager, displayId, brightness);
    }
}
