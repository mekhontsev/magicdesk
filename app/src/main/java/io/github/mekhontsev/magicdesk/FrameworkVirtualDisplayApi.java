package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;

import java.lang.reflect.Method;

/** Android 15+ display primitives used by the shell-owned display catalog. */
final class FrameworkVirtualDisplayApi {
    private final Method mType = Display.class.getMethod("getType");
    private final Method mUniqueId = Display.class.getMethod("getUniqueId");
    private final int mInternal = displayConstant("TYPE_INTERNAL");
    private final int mExternal = displayConstant("TYPE_EXTERNAL");
    private final int mWifi = displayConstant("TYPE_WIFI");
    private final int mOverlay = displayConstant("TYPE_OVERLAY");
    private final int mVirtual = displayConstant("TYPE_VIRTUAL");
    private final int mTrusted = displayConstant("FLAG_TRUSTED");
    private static int creationFlags() throws ReflectiveOperationException {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | virtualFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
            | virtualFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
            | virtualFlag("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS")
            | virtualFlag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP");
    }

    FrameworkVirtualDisplayApi() throws ReflectiveOperationException { }

    OwnedDisplay create(final Context context, final VirtualDisplaySpec spec)
            throws ReflectiveOperationException {
        final DisplayManager manager = context.getSystemService(DisplayManager.class);
        if (manager == null) { throw new IllegalStateException("display service unavailable"); }
        final int flags = creationFlags();
        final ImageReader output = ImageReader.newInstance(
                spec.width, spec.height, PixelFormat.RGBA_8888, 2);
        VirtualDisplay display = null;
        try {
            // Android 16 requires a Surface for an ON virtual display. Use the
            // same frame-consumer lifecycle on Android 15, without a polling
            // loop or a per-display thread. scrcpy captures the logical scene.
            display = manager.createVirtualDisplay(
                    "MagicDesk Computer", spec.width, spec.height, spec.densityDpi,
                    output.getSurface(), flags);
            if (display == null) { throw new IllegalStateException("Android did not create the virtual display"); }
            return new OwnedDisplay(display, output);
        } catch (RuntimeException error) {
            if (display != null) { display.release(); }
            output.close();
            throw error;
        }
    }

    static final class OwnedDisplay {
        private final VirtualDisplay mDisplay;
        private final ImageReader mOutput;
        private boolean mReleased;

        OwnedDisplay(final VirtualDisplay display, final ImageReader output) {
            mDisplay = display;
            mOutput = output;
            output.setOnImageAvailableListener(reader -> discardFrame(),
                    new Handler(Looper.getMainLooper()));
        }

        Display getDisplay() { return mDisplay.getDisplay(); }

        private synchronized void discardFrame() {
            if (mReleased) { return; }
            try (Image ignored = mOutput.acquireLatestImage()) { }
        }

        synchronized void release() {
            if (mReleased) { return; }
            mDisplay.release();
            mReleased = true;
            mOutput.setOnImageAvailableListener(null, null);
            mOutput.close();
        }
    }

    DesktopDisplayInfo describe(final Display display, final boolean owned)
            throws ReflectiveOperationException {
        final int type = (int) mType.invoke(display);
        final int id = display.getDisplayId();
        final String source = id == Display.DEFAULT_DISPLAY ? "phone"
                : type == mExternal ? "wired" : type == mWifi ? "wireless"
                : type == mOverlay ? "overlay" : type == mVirtual ? "virtual"
                : type == mInternal ? "internal" : "unknown";
        final DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        final boolean supported = !"unknown".equals(source) && !"internal".equals(source)
                && (id == Display.DEFAULT_DISPLAY
                    || ((display.getFlags() & Display.FLAG_PRIVATE) == 0
                        && (display.getFlags() & mTrusted) != 0));
        return new DesktopDisplayInfo(id, (String) mUniqueId.invoke(display), display.getName(),
                source, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                supported, owned);
    }

    private static int displayConstant(final String name) throws ReflectiveOperationException {
        return Display.class.getField(name).getInt(null);
    }

    private static int virtualFlag(final String name) throws ReflectiveOperationException {
        return DisplayManager.class.getField(name).getInt(null);
    }
}
