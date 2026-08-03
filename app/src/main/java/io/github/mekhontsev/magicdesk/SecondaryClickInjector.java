package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Point;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

final class SecondaryClickInjector {
    private static final int INJECTION_MODE_WAIT_FOR_RESULT = 1;

    private SecondaryClickInjector() {
    }

    @SuppressLint("BlockedPrivateApi")
    static void inject(final int displayId) {
        if (displayId <= 0) {
            throw new IllegalArgumentException("missing target display");
        }
        try {
            final Point position = NubiaMouseController.getPosition();
            final Object inputManager = getInputManager();
            final Method inject = findInjectMethod();
            final Method setDisplayId = InputEvent.class.getDeclaredMethod(
                    "setDisplayId", int.class);
            setDisplayId.setAccessible(true);
            final Method setActionButton = MotionEvent.class.getMethod(
                    "setActionButton", int.class);
            final long downTime = SystemClock.uptimeMillis();
            inject(inputManager, inject, setDisplayId, setActionButton,
                    displayId, position, downTime,
                    MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY, 0);
            inject(inputManager, inject, setDisplayId, setActionButton,
                    displayId, position, downTime,
                    MotionEvent.ACTION_BUTTON_PRESS,
                    MotionEvent.BUTTON_SECONDARY,
                    MotionEvent.BUTTON_SECONDARY);
            inject(inputManager, inject, setDisplayId, setActionButton,
                    displayId, position, downTime,
                    MotionEvent.ACTION_BUTTON_RELEASE, 0,
                    MotionEvent.BUTTON_SECONDARY);
            inject(inputManager, inject, setDisplayId, setActionButton,
                    displayId, position, downTime,
                    MotionEvent.ACTION_UP, 0, 0);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "could not inject secondary click", error);
        }
    }

    private static void inject(
            final Object inputManager,
            final Method inject,
            final Method setDisplayId,
            final Method setActionButton,
            final int displayId,
            final Point position,
            final long downTime,
            final int action,
            final int buttonState,
            final int actionButton) throws ReflectiveOperationException {
        final MotionEvent.PointerProperties properties =
                new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        final MotionEvent.PointerCoords coordinates =
                new MotionEvent.PointerCoords();
        coordinates.x = position.x;
        coordinates.y = position.y;
        final MotionEvent event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[] {properties},
                new MotionEvent.PointerCoords[] {coordinates},
                0, buttonState, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        try {
            setActionButton.invoke(event, Integer.valueOf(actionButton));
            setDisplayId.invoke(event, Integer.valueOf(displayId));
            final Object result = inject.getParameterCount() == 2
                    ? inject.invoke(inputManager, event,
                            Integer.valueOf(INJECTION_MODE_WAIT_FOR_RESULT))
                    : inject.invoke(inputManager, event,
                            Integer.valueOf(INJECTION_MODE_WAIT_FOR_RESULT),
                            Integer.valueOf(-1));
            if (result instanceof Boolean
                    && !((Boolean) result).booleanValue()) {
                throw new IllegalStateException("input injection was rejected");
            }
        } finally {
            event.recycle();
        }
    }

    private static Object getInputManager() throws ReflectiveOperationException {
        final Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        final IBinder binder = (IBinder) serviceManager
                .getMethod("getService", String.class)
                .invoke(null, "input");
        return Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }

    private static Method findInjectMethod() throws ReflectiveOperationException {
        final Class<?> type = Class.forName("android.hardware.input.IInputManager");
        for (final Method method : type.getMethods()) {
            if (("injectInputEvent".equals(method.getName())
                    || "injectInputEventToTarget".equals(method.getName()))
                    && method.getParameterCount() >= 2
                    && InputEvent.class.isAssignableFrom(
                            method.getParameterTypes()[0])) {
                return method;
            }
        }
        throw new NoSuchMethodException("IInputManager input injection");
    }
}
