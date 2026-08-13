package io.github.mekhontsev.magicdesk;

import android.graphics.Point;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class NubiaMouseController {
    private static final int MOUSE_CMD_CREATE_OR_UPDATE = 0;
    private static final int MOUSE_CMD_SHOW = 2;
    private static final String INPUT_MANAGER_DESCRIPTOR =
            "android.hardware.input.IInputManager";
    private static final String SET_POINTER_POSITION_TRANSACTION =
            "setPointerPosition";
    private static volatile int sSetPointerPositionTransaction;
    private static volatile MousePositionAccess sMousePositionAccess;
    private static int sKnownMouseDisplayId = -1;
    private static Point sKnownMousePosition;

    private NubiaMouseController() {
    }

    static void setPosition(final Point position)
            throws ReflectiveOperationException {
        if (position == null) {
            throw new IllegalArgumentException("missing pointer position");
        }
        final IBinder binder = getInputManagerBinder();
        final int transaction = getSetPointerPositionTransaction();
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INPUT_MANAGER_DESCRIPTOR);
            data.writeInt(position.x);
            data.writeInt(position.y);
            // Nubia declares this call oneway. A synchronous transaction makes
            // completion observable without changing the server operation.
            if (!binder.transact(transaction, data, reply, 0)) {
                throw new IllegalStateException(
                        "vendor input service rejected pointer position");
            }
        } catch (RemoteException error) {
            throw new IllegalStateException(
                    "vendor input service is unavailable", error);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static void setMousePosition(
            final int displayId,
            final Point position)
            throws ReflectiveOperationException {
        if (displayId <= 0) {
            throw new IllegalArgumentException("missing mouse display");
        }
        if (position == null) {
            throw new IllegalArgumentException("missing mouse position");
        }
        final MousePositionAccess access = mousePositionAccess();
        access.setMousePosition.invoke(access.inputManager,
                        Integer.valueOf(position.x),
                        Integer.valueOf(position.y));
        rememberPosition(displayId, position);
    }

    static void showMouse() throws ReflectiveOperationException {
        final MousePositionAccess access = mousePositionAccess();
        access.sendMouseCommand.invoke(
                access.inputManager, Integer.valueOf(MOUSE_CMD_SHOW));
    }

    static void createOrUpdateViewport()
            throws ReflectiveOperationException {
        final MousePositionAccess access = mousePositionAccess();
        access.sendMouseCommand.invoke(
                access.inputManager,
                Integer.valueOf(MOUSE_CMD_CREATE_OR_UPDATE));
    }

    static Point getPosition() throws ReflectiveOperationException {
        final Point position = queryPosition();
        if (position == null) {
            throw new IllegalStateException(
                    "vendor input service returned no pointer position");
        }
        return position;
    }

    static Point getPosition(final int displayId)
            throws ReflectiveOperationException {
        if (displayId <= 0) {
            throw new IllegalArgumentException("missing mouse display");
        }
        final Point displaySize = getLogicalDisplaySize(displayId);
        Point position = null;
        synchronized (NubiaMouseController.class) {
            if (sKnownMouseDisplayId == displayId
                    && sKnownMousePosition != null) {
                position = new Point(sKnownMousePosition);
            }
        }
        if (position == null) {
            position = new Point(
                    (displaySize.x - 1) / 2,
                    (displaySize.y - 1) / 2);
        }
        position.x = clamp(position.x, displaySize.x - 1);
        position.y = clamp(position.y, displaySize.y - 1);
        return position;
    }

    private static Point queryPosition()
            throws ReflectiveOperationException {
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

    private static Point getLogicalDisplaySize(final int displayId)
            throws ReflectiveOperationException {
        final Object displayManager = Class.forName(
                "android.hardware.display.IDisplayManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, getServiceBinder("display"));
        final Object displayInfo = Class.forName(
                "android.hardware.display.IDisplayManager")
                .getMethod("getDisplayInfo", int.class)
                .invoke(displayManager, Integer.valueOf(displayId));
        if (displayInfo == null) {
            throw new IllegalStateException(
                    "mouse display is unavailable: " + displayId);
        }
        final int width = ((Number) displayInfo.getClass()
                .getField("logicalWidth").get(displayInfo)).intValue();
        final int height = ((Number) displayInfo.getClass()
                .getField("logicalHeight").get(displayInfo)).intValue();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException(
                    "mouse display has invalid bounds: "
                            + width + "x" + height);
        }
        return new Point(width, height);
    }

    static synchronized void rememberPosition(
            final int displayId,
            final Point position) {
        sKnownMouseDisplayId = displayId;
        sKnownMousePosition = new Point(position);
    }

    private static int clamp(final int value, final int maximum) {
        return Math.max(0, Math.min(maximum, value));
    }

    static void preparePointerPositionControl()
            throws ReflectiveOperationException {
        getSetPointerPositionTransaction();
    }

    static void prepareMousePositionControl()
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

    private static int findTransactionCode(final String name)
            throws ReflectiveOperationException {
        final Method transactionName = Class.forName(
                "android.hardware.input.IInputManager$Stub")
                .getMethod("getDefaultTransactionName", int.class);
        for (int code = 1; code <= 256; ++code) {
            if (name.equals(transactionName.invoke(null, code))) {
                return code;
            }
        }
        throw new NoSuchMethodException(
                "missing IInputManager transaction " + name);
    }

    private static int getSetPointerPositionTransaction()
            throws ReflectiveOperationException {
        int transaction = sSetPointerPositionTransaction;
        if (transaction != 0) {
            return transaction;
        }
        synchronized (NubiaMouseController.class) {
            transaction = sSetPointerPositionTransaction;
            if (transaction == 0) {
                transaction = findTransactionCode(
                        SET_POINTER_POSITION_TRANSACTION);
                sSetPointerPositionTransaction = transaction;
            }
        }
        return transaction;
    }

    private static MousePositionAccess mousePositionAccess()
            throws ReflectiveOperationException {
        MousePositionAccess access = sMousePositionAccess;
        if (access != null) {
            return access;
        }
        synchronized (NubiaMouseController.class) {
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
        final Method setMousePosition;
        final Method sendMouseCommand;

        MousePositionAccess() throws ReflectiveOperationException {
            inputManager = getInputManager();
            final Class<?> type = Class.forName(
                    "android.hardware.input.IInputManager");
            getMousePosition = type.getMethod(
                    "getMousePosition", Point.class);
            setMousePosition = type.getMethod(
                    "setMousePosition", int.class, int.class);
            sendMouseCommand = type.getMethod("sendMouseCmd", int.class);
        }
    }

    static Throwable usefulCause(final ReflectiveOperationException error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null) {
            return ((InvocationTargetException) error).getCause();
        }
        return error;
    }
}
