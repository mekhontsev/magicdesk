package io.github.mekhontsev.magicdesk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Android 15+ display-default configuration through WindowManager. */
final class FrameworkDisplayWindowingApi {
    private final Object mDisplayManager;
    private final Object mWindowManager;
    private final Method mGetDisplayInfo;
    private final Method mGetMode;
    private final Method mSetMode;
    private final Field mUniqueId;
    private final Field mType;
    private final int mVirtualDisplayType;

    FrameworkDisplayWindowingApi() throws ReflectiveOperationException {
        final Class<?> displays = Class.forName(
                "android.hardware.display.DisplayManagerGlobal");
        mDisplayManager = displays.getMethod("getInstance").invoke(null);
        mGetDisplayInfo = displays.getMethod("getDisplayInfo", Integer.TYPE);
        final Class<?> info = Class.forName("android.view.DisplayInfo");
        mUniqueId = info.getField("uniqueId");
        mType = info.getField("type");
        mVirtualDisplayType = android.view.Display.class.getField("TYPE_VIRTUAL").getInt(null);
        mWindowManager = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
        final Class<?> windows = Class.forName("android.view.IWindowManager");
        mGetMode = windows.getMethod("getWindowingMode", Integer.TYPE);
        mSetMode = windows.getMethod("setWindowingMode", Integer.TYPE, Integer.TYPE);
    }

    DisplayWindowingSnapshot read(final int displayId)
            throws ReflectiveOperationException {
        final Object info = mGetDisplayInfo.invoke(mDisplayManager, displayId);
        if (info == null) {
            return null;
        }
        // IWindowManager resolves UNDEFINED using framework desktop policy.
        // This is the effective mode, not the raw persistent override.
        final int mode = ((Integer) mGetMode.invoke(mWindowManager, displayId)).intValue();
        return new DisplayWindowingSnapshot(displayId, (String) mUniqueId.get(info),
                mode, mType.getInt(info) == mVirtualDisplayType);
    }

    void set(final int displayId, final String uniqueId, final int mode)
            throws ReflectiveOperationException {
        if (displayId <= 0 || mode <= 0 || uniqueId == null || uniqueId.isEmpty()) {
            throw new IllegalArgumentException("invalid secondary display mode request");
        }
        final DisplayWindowingSnapshot before = read(displayId);
        if (before == null || !uniqueId.equals(before.uniqueId)) {
            throw new IllegalStateException("secondary display identity changed");
        }
        mSetMode.invoke(mWindowManager, displayId, mode);
        final DisplayWindowingSnapshot after = read(displayId);
        if (after == null || !uniqueId.equals(after.uniqueId) || after.mode != mode) {
            throw new IllegalStateException("display default mode was not applied");
        }
    }
}
