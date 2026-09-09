package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;

import java.io.IOException;

/** Logical-display capture through WindowManager on Android 15 and newer. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class FrameworkDisplayCaptureApi {
    Bitmap capture(
            final int displayId,
            final Rect crop,
            final int width,
            final int height) throws ReflectiveOperationException, IOException {
        final Class<?> captureClass = Class.forName("android.window.ScreenCapture");
        final Class<?> argsClass = Class.forName("android.window.ScreenCapture$CaptureArgs");
        final Class<?> builderClass = Class.forName(
                "android.window.ScreenCapture$CaptureArgs$Builder");
        final Class<?> listenerClass = Class.forName(
                "android.window.ScreenCapture$ScreenCaptureListener");
        final Object builder = builderClass.getConstructor().newInstance();
        builderClass.getMethod("setSourceCrop", Rect.class)
                .invoke(builder, new Rect(crop));
        builderClass.getMethod("setFrameScale", Float.TYPE, Float.TYPE)
                .invoke(builder, captureScale(crop.width(), width), captureScale(crop.height(), height));
        final Object args = builderClass.getMethod("build").invoke(builder);
        final Object listener = captureClass.getMethod("createSyncCaptureListener").invoke(null);
        final Object windows = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
        // WM resolves the logical display to its current layer tree, including
        // virtual displays. A SurfaceFlinger physical-display token is not required.
        Class.forName("android.view.IWindowManager")
                .getMethod("captureDisplay", Integer.TYPE, argsClass, listenerClass)
                .invoke(windows, displayId, args, listener);
        EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.DISPLAY_CAPTURE);
        final Object screenshot = Class.forName(
                "android.window.ScreenCapture$SynchronousScreenCaptureListener")
                .getMethod("getBuffer").invoke(listener);
        if (screenshot == null) {
            throw new IOException("display capture returned no buffer for " + displayId);
        }
        Bitmap hardwareBitmap = null;
        HardwareBuffer hardwareBuffer = null;
        try {
            hardwareBuffer = (HardwareBuffer) screenshot.getClass()
                    .getMethod("getHardwareBuffer").invoke(screenshot);
            hardwareBitmap = (Bitmap) screenshot.getClass().getMethod("asBitmap")
                    .invoke(screenshot);
            if (hardwareBitmap == null) {
                throw new IOException("display capture returned no bitmap");
            }
            if (hardwareBitmap.getWidth() != width || hardwareBitmap.getHeight() != height) {
                throw new IOException("display capture returned "
                        + hardwareBitmap.getWidth() + "x" + hardwareBitmap.getHeight()
                        + ", expected " + width + "x" + height
                        + " for crop " + crop.toShortString());
            }
            final Bitmap bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (bitmap == null) {
                throw new IOException("display capture could not be read");
            }
            return bitmap;
        } finally {
            if (hardwareBitmap != null) {
                hardwareBitmap.recycle();
            }
            if (hardwareBuffer != null) {
                hardwareBuffer.close();
            }
        }
    }

    static float captureScale(final int sourceSize, final int outputSize) {
        if (sourceSize <= 0 || outputSize <= 0) {
            throw new IllegalArgumentException("capture dimensions must be positive");
        }
        // SurfaceFlinger truncates scaled dimensions to integers. Round the
        // ratio upward when float precision would lose the last output pixel.
        final float scale = (float) outputSize / sourceSize;
        return (double) scale * sourceSize < outputSize ? Math.nextUp(scale) : scale;
    }
}
