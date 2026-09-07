package io.github.mekhontsev.magicdesk.platform.nubia;

import android.graphics.Point;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.Method;

final class NubiaDesktopPointerController {
    private static final int MOUSE_CMD_CREATE_OR_UPDATE = 0;
    private static final String INPUT_MANAGER_DESCRIPTOR =
            "android.hardware.input.IInputManager";
    private static final String SEND_MOUSE_COMMAND_TRANSACTION =
            "sendMouseCmd";
    private static volatile int sSendMouseCommandTransaction;
    private static volatile MousePositionAccess sMousePositionAccess;

    private NubiaDesktopPointerController() {
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
    }

    static void createOrUpdateViewport()
            throws ReflectiveOperationException {
        final IBinder binder = getInputManagerBinder();
        final int transaction = getSendMouseCommandTransaction();
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INPUT_MANAGER_DESCRIPTOR);
            data.writeInt(MOUSE_CMD_CREATE_OR_UPDATE);
            // Nubia declares sendMouseCmd oneway. A synchronous transaction
            // keeps capture from overtaking the service-side viewport request;
            // InputReader may still apply the accepted update asynchronously.
            if (!binder.transact(transaction, data, reply, 0)) {
                throw new IllegalStateException(
                        "vendor input service rejected viewport refresh");
            }
        } catch (RemoteException error) {
            throw new IllegalStateException(
                    "vendor input service is unavailable", error);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static Point getPosition() throws ReflectiveOperationException {
        final Point position = queryPosition();
        if (position == null) {
            throw new IllegalStateException(
                    "vendor input service returned no pointer position");
        }
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

    private static int getSendMouseCommandTransaction()
            throws ReflectiveOperationException {
        int transaction = sSendMouseCommandTransaction;
        if (transaction != 0) {
            return transaction;
        }
        synchronized (NubiaDesktopPointerController.class) {
            transaction = sSendMouseCommandTransaction;
            if (transaction == 0) {
                transaction = findTransactionCode(
                        SEND_MOUSE_COMMAND_TRANSACTION);
                sSendMouseCommandTransaction = transaction;
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
        final Method setMousePosition;

        MousePositionAccess() throws ReflectiveOperationException {
            inputManager = getInputManager();
            final Class<?> type = Class.forName(
                    "android.hardware.input.IInputManager");
            getMousePosition = type.getMethod(
                    "getMousePosition", Point.class);
            setMousePosition = type.getMethod(
                    "setMousePosition", int.class, int.class);
        }
    }

}
