package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.graphics.PixelFormat;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Display;

import java.lang.reflect.Method;

/** Shared display primitives used by the privileged display resource owner. */
final class FrameworkVirtualDisplayApi {
    private static final String SECURE_OUTPUT_PERMISSION = "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT";

    static boolean canCreateProtectedDisplay(final Context context) {
        return context.checkPermission(SECURE_OUTPUT_PERMISSION,
                android.os.Process.myPid(), android.os.Process.myUid())
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private final Method mType = Display.class.getMethod("getType");
    private final Method mUniqueId = Display.class.getMethod("getUniqueId");
    private final int mInternal = displayConstant("TYPE_INTERNAL");
    private final int mExternal = displayConstant("TYPE_EXTERNAL");
    private final int mWifi = displayConstant("TYPE_WIFI");
    private final int mOverlay = displayConstant("TYPE_OVERLAY");
    private final int mVirtual = displayConstant("TYPE_VIRTUAL");
    private final int mTrusted = displayConstant("FLAG_TRUSTED");
    private static int creationFlags() throws ReflectiveOperationException {
        // Desktop launches its HOME root explicitly; do not request SystemUI navigation.
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | virtualFlag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
            | virtualFlag("VIRTUAL_DISPLAY_FLAG_TRUSTED")
            | virtualFlag("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP");
    }

    FrameworkVirtualDisplayApi() throws ReflectiveOperationException { }

    OwnedDisplay create(final Context context, final VirtualDisplaySpec spec)
            throws ReflectiveOperationException {
        final DisplayManager manager = context.getSystemService(DisplayManager.class);
        if (manager == null) { throw new IllegalStateException("display service unavailable"); }
        if (spec.protectedContent && !canCreateProtectedDisplay(context)) {
            throw new SecurityException("Protected content requires " + SECURE_OUTPUT_PERMISSION
                    + " in the current privileged service");
        }
        final int flags = creationFlags() | (spec.protectedContent
                ? DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE : 0);
        // A secure source must never return to a CPU-readable consumer on Detach.
        final ImageReader output = spec.protectedContent
                ? ImageReader.newInstance(spec.width, spec.height, ImageFormat.PRIVATE, 2,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_PROTECTED_CONTENT)
                : ImageReader.newInstance(spec.width, spec.height, PixelFormat.RGBA_8888, 2);
        VirtualDisplay display = null;
        try {
            // Android 16 requires a Surface for an ON virtual display. Use the
            // same frame-consumer lifecycle on Android 15, without a polling
            // loop or a per-display thread. scrcpy captures the logical scene.
            display = manager.createVirtualDisplay(
                    "MagicDesk", spec.width, spec.height, spec.densityDpi,
                    output.getSurface(), flags);
            if (display == null) { throw new IllegalStateException("Android did not create the virtual display"); }
            if (spec.protectedContent && (display.getDisplay().getFlags() & Display.FLAG_SECURE) == 0) {
                throw new IllegalStateException("Android did not create a secure virtual display");
            }
            return new OwnedDisplay(context, display, output);
        } catch (RuntimeException error) {
            if (display != null) { display.release(); }
            output.close();
            throw error;
        }
    }

    static final class OwnedDisplay {
        private final VirtualDisplay mDisplay;
        private final ImageReader mOutput;
        private final Context mContext;
        private PowerManager.WakeLock mPresentationWakeLock;
        private int mPresentations;
        private boolean mReleased;

        OwnedDisplay(final Context context, final VirtualDisplay display, final ImageReader output) {
            mContext = context;
            mDisplay = display;
            mOutput = output;
            output.setOnImageAvailableListener(reader -> discardFrame(),
                    new Handler(Looper.getMainLooper()));
        }

        Display getDisplay() { return mDisplay.getDisplay(); }

        synchronized void present(final android.view.Surface surface) {
            if (mReleased) { throw new IllegalStateException("virtual display was removed"); }
            if (surface == null || !surface.isValid()) {
                throw new IllegalArgumentException("a live viewer surface is required");
            }
            mDisplay.setSurface(surface);
        }

        synchronized void detachViewer() {
            // Keep a render target and task configuration when the viewer
            // disappears. Ordinary idle sleep is allowed while detached.
            if (!mReleased) mDisplay.setSurface(mOutput.getSurface());
        }

        DisplayPresentationSurface presentation(DisplayPresentationSurface delegate) {
            return new DisplayPresentationSurface() {
                private boolean acquired;
                private boolean closed;
                @Override public synchronized void attach(android.view.Surface surface,
                        android.view.SurfaceControl parent) {
                    if (closed) throw new IllegalStateException("presentation was closed");
                    if (!acquired) { acquirePresentation(); acquired = true; }
                    try { delegate.attach(surface, parent); }
                    catch (RuntimeException error) {
                        try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                        throw error;
                    }
                }
                @Override public synchronized void close() {
                    if (closed) return;
                    closed = true;
                    try { delegate.close(); }
                    finally {
                        if (acquired) { acquired = false; releasePresentation(); }
                    }
                }
            };
        }

        private synchronized void acquirePresentation() {
            if (mReleased) throw new IllegalStateException("virtual display was removed");
            if (mPresentations == 0) keepPresentationAwake();
            mPresentations++;
        }

        private synchronized void releasePresentation() {
            if (--mPresentations == 0) releasePresentationWakeLock();
        }

        @SuppressWarnings("deprecation")
        private void keepPresentationAwake() {
            if (mPresentationWakeLock == null) {
                final PowerManager power = mContext.getSystemService(PowerManager.class);
                try {
                    // Android 14/15's two-argument API affects every power group.
                    // A viewer must wake/retain only its owned virtual source.
                    mPresentationWakeLock = (PowerManager.WakeLock) PowerManager.class.getMethod(
                            "newWakeLock", int.class, String.class, int.class).invoke(power,
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "MagicDesk:DisplayViewer", mDisplay.getDisplay().getDisplayId());
                    mPresentationWakeLock.setReferenceCounted(false);
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("could not acquire source display power", error);
                }
            }
            if (!mPresentationWakeLock.isHeld()) mPresentationWakeLock.acquire();
        }

        private void releasePresentationWakeLock() {
            if (mPresentationWakeLock != null && mPresentationWakeLock.isHeld()) {
                mPresentationWakeLock.release();
            }
        }

        private synchronized void discardFrame() {
            if (mReleased) { return; }
            try (Image ignored = mOutput.acquireLatestImage()) { }
        }

        synchronized void release() {
            if (mReleased) { return; }
            try { mDisplay.release(); }
            finally { releasePresentationWakeLock(); }
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
        final boolean publicDisplay = (display.getFlags() & Display.FLAG_PRIVATE) == 0;
        final boolean trusted = (display.getFlags() & mTrusted) != 0;
        final boolean supported = DesktopDisplayInfo.supportsDesktop(id, source, publicDisplay, trusted);
        return new DesktopDisplayInfo(id, (String) mUniqueId.invoke(display), display.getName(),
                source, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                supported, DesktopDisplayInfo.requiresPortableDesktop(id, source, publicDisplay, trusted),
                owned, (display.getFlags() & Display.FLAG_SECURE) != 0);
    }

    private static int displayConstant(final String name) throws ReflectiveOperationException {
        return Display.class.getField(name).getInt(null);
    }

    private static int virtualFlag(final String name) throws ReflectiveOperationException {
        return DisplayManager.class.getField(name).getInt(null);
    }
}
