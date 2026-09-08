package io.github.mekhontsev.magicdesk.platform.nubia;

import android.graphics.Point;
import android.os.IBinder;

import java.lang.reflect.Method;

final class NubiaDesktopPointerController {
    private static volatile MousePositionAccess sMousePositionAccess;

    private NubiaDesktopPointerController() {
    }

    static Point getPosition() throws ReflectiveOperationException {
        final Point position = new Point();
        final MousePositionAccess access = mousePositionAccess();
        final Object result = access.getMousePosition.invoke(
                access.inputManager, position);
        if (!(result instanceof Boolean)
                || !((Boolean) result).booleanValue()) {
            return null;
        }
        return position;
    }

    static void prepareMousePositionObservation()
            throws ReflectiveOperationException {
        mousePositionAccess();
    }

    private static Object getInputManager() throws ReflectiveOperationException {
        return Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, getInputManagerBinder());
    }

    private static IBinder getInputManagerBinder()
            throws ReflectiveOperationException {
        return getServiceBinder("input");
    }

    private static IBinder getServiceBinder(final String serviceName)
            throws ReflectiveOperationException {
        final Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        final IBinder binder = (IBinder) serviceManagerClass
                .getMethod("getService", String.class)
                .invoke(null, serviceName);
        if (binder == null) {
            throw new IllegalStateException("input service is unavailable");
        }
        return binder;
    }

    private static MousePositionAccess mousePositionAccess()
            throws ReflectiveOperationException {
        MousePositionAccess access = sMousePositionAccess;
        if (access != null) {
            return access;
        }
        synchronized (NubiaDesktopPointerController.class) {
            access = sMousePositionAccess;
            if (access == null) {
                access = new MousePositionAccess();
                sMousePositionAccess = access;
            }
        }
        return access;
    }

    private static final class MousePositionAccess {
        final Object inputManager;
        final Method getMousePosition;

        MousePositionAccess() throws ReflectiveOperationException {
            inputManager = getInputManager();
            final Class<?> type = Class.forName(
                    "android.hardware.input.IInputManager");
            getMousePosition = type.getMethod(
                    "getMousePosition", Point.class);
        }
    }

}
