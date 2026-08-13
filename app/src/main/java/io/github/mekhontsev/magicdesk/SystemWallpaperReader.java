package io.github.mekhontsev.magicdesk;

import android.app.WallpaperManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reads the current wallpaper using the shell identity of the UserService. */
final class SystemWallpaperReader {
    private static final String SHELL_PACKAGE = "com.android.shell";

    private SystemWallpaperReader() {
    }

    static ParcelFileDescriptor openCurrent() {
        RuntimeException systemError = null;
        try {
            final ParcelFileDescriptor wallpaper = openAospCurrent();
            if (wallpaper != null) {
                return wallpaper;
            }
        } catch (RuntimeException error) {
            systemError = error;
        }
        final ParcelFileDescriptor platformWallpaper =
                PlatformDrivers.current().wallpaper().openCurrentFallback();
        if (platformWallpaper != null) {
            return platformWallpaper;
        }
        if (systemError != null) {
            throw systemError;
        }
        return null;
    }

    private static ParcelFileDescriptor openAospCurrent() {
        try {
            final Class<?> serviceManager = Class.forName(
                    "android.os.ServiceManager");
            final IBinder binder = (IBinder) serviceManager
                    .getMethod("getService", String.class)
                    .invoke(null, "wallpaper");
            if (binder == null) {
                throw new IllegalStateException(
                        "Android wallpaper service is unavailable");
            }
            final Class<?> stub = Class.forName(
                    "android.app.IWallpaperManager$Stub");
            final Object service = stub
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            final Method getWallpaper = findGetWallpaperMethod(
                    service.getClass());
            return (ParcelFileDescriptor) getWallpaper.invoke(
                    service,
                    SHELL_PACKAGE,
                    null,
                    null,
                    Integer.valueOf(WallpaperManager.FLAG_SYSTEM),
                    new Bundle(),
                    Integer.valueOf(currentUserId()),
                    Boolean.TRUE);
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            throw new IllegalStateException(
                    "current system wallpaper read failed: "
                            + usefulMessage(cause),
                    cause == null ? error : cause);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "current system wallpaper API is unavailable: "
                            + usefulMessage(error),
                    error);
        }
    }

    private static int currentUserId() throws ReflectiveOperationException {
        return ((Integer) Class.forName("android.os.UserHandle")
                .getMethod("myUserId")
                .invoke(null)).intValue();
    }

    private static Method findGetWallpaperMethod(final Class<?> serviceClass)
            throws NoSuchMethodException {
        for (final Method method : serviceClass.getMethods()) {
            if (!"getWallpaperWithFeature".equals(method.getName())) {
                continue;
            }
            final Class<?>[] types = method.getParameterTypes();
            if (types.length == 7
                    && types[0] == String.class
                    && types[1] == String.class
                    && !types[2].isPrimitive()
                    && types[3] == Integer.TYPE
                    && types[4] == Bundle.class
                    && types[5] == Integer.TYPE
                    && types[6] == Boolean.TYPE) {
                return method;
            }
        }
        throw new NoSuchMethodException(
                "supported getWallpaperWithFeature signature on "
                        + serviceClass.getName());
    }

    private static String usefulMessage(final Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }
}
