package io.github.mekhontsev.magicdesk;

import android.graphics.Point;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class NubiaMouseController {
    private static final int MOUSE_CMD_CREATE_OR_UPDATE = 0;
    private static final String INPUT_MANAGER_DESCRIPTOR =
            "android.hardware.input.IInputManager";
    private static final String SET_POINTER_POSITION_TRANSACTION =
            "setPointerPosition";
    private static volatile int sSetPointerPositionTransaction;

    private NubiaMouseController() {
    }

    static void createOrUpdateViewport() throws ReflectiveOperationException {
        final Object inputManager = getInputManager();
        getInputManagerMethod("sendMouseCmd", int.class)
                .invoke(inputManager, Integer.valueOf(MOUSE_CMD_CREATE_OR_UPDATE));
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

    static Point getPosition() throws ReflectiveOperationException {
        final Point position = new Point();
        final Object inputManager = getInputManager();
        final Object result = getInputManagerMethod(
                "getMousePosition", Point.class).invoke(
                        inputManager, position);
        if (!(result instanceof Boolean)
                || !((Boolean) result).booleanValue()) {
            throw new IllegalStateException(
                    "vendor input service returned no pointer position");
        }
        return position;
    }

    static void preparePositionControl()
            throws ReflectiveOperationException {
        getSetPointerPositionTransaction();
    }

    private static Object getInputManager() throws ReflectiveOperationException {
        return Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, getInputManagerBinder());
    }

    private static IBinder getInputManagerBinder()
            throws ReflectiveOperationException {
        final Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        final IBinder binder = (IBinder) serviceManagerClass
                .getMethod("getService", String.class)
                .invoke(null, "input");
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

    private static Method getInputManagerMethod(
            final String name,
            final Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        return Class.forName("android.hardware.input.IInputManager")
                .getMethod(name, parameterTypes);
    }

    static Throwable usefulCause(final ReflectiveOperationException error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null) {
            return ((InvocationTargetException) error).getCause();
        }
        return error;
    }
}
