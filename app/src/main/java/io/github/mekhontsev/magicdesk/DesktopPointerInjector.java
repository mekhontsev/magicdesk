package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Point;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/** Injects pointer actions that the virtual mouse device cannot express. */
final class DesktopPointerInjector {
    private static final int INJECTION_MODE_WAIT_FOR_RESULT = 1;

    private DesktopPointerInjector() {
    }

    @SuppressLint("BlockedPrivateApi")
    static void injectSecondaryClick(final int displayId) {
        validateDisplay(displayId);
        try {
            final Point position = NubiaMouseController.getPosition();
            final InjectionContext context = new InjectionContext();
            final long downTime = SystemClock.uptimeMillis();
            context.injectMouse(displayId, position, downTime,
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.BUTTON_SECONDARY, 0);
            context.injectMouse(displayId, position, downTime,
                    MotionEvent.ACTION_BUTTON_PRESS,
                    MotionEvent.BUTTON_SECONDARY,
                    MotionEvent.BUTTON_SECONDARY);
            context.injectMouse(displayId, position, downTime,
                    MotionEvent.ACTION_BUTTON_RELEASE,
                    0, MotionEvent.BUTTON_SECONDARY);
            context.injectMouse(displayId, position, downTime,
                    MotionEvent.ACTION_UP, 0, 0);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "could not inject secondary click", error);
        }
    }

    @SuppressLint("BlockedPrivateApi")
    static void injectTouchTap(final int displayId) {
        validateDisplay(displayId);
        try {
            final Point position = NubiaMouseController.getPosition();
            final InjectionContext context = new InjectionContext();
            final long downTime = SystemClock.uptimeMillis();
            context.injectTouch(displayId, position, downTime,
                    MotionEvent.ACTION_DOWN, 1.0f);
            context.injectTouch(displayId, position, downTime,
                    MotionEvent.ACTION_UP, 0.0f);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "could not inject touchscreen tap", error);
        }
    }

    private static void validateDisplay(final int displayId) {
        if (displayId <= 0) {
            throw new IllegalArgumentException("missing target display");
        }
    }

    private static final class InjectionContext {
        private final Object mInputManager;
        private final Method mInject;
        private final Method mSetDisplayId;
        private final Method mSetActionButton;

        @SuppressLint("BlockedPrivateApi")
        InjectionContext() throws ReflectiveOperationException {
            mInputManager = getInputManager();
            mInject = findInjectMethod();
            mSetDisplayId = InputEvent.class.getDeclaredMethod(
                    "setDisplayId", int.class);
            mSetDisplayId.setAccessible(true);
            mSetActionButton = MotionEvent.class.getMethod(
                    "setActionButton", int.class);
        }

        void injectMouse(
                final int displayId,
                final Point position,
                final long downTime,
                final int action,
                final int buttonState,
                final int actionButton)
                throws ReflectiveOperationException {
            inject(displayId, position, downTime, action,
                    MotionEvent.TOOL_TYPE_MOUSE,
                    InputDevice.SOURCE_MOUSE,
                    buttonState,
                    actionButton,
                    0.0f);
        }

        void injectTouch(
                final int displayId,
                final Point position,
                final long downTime,
                final int action,
                final float pressure)
                throws ReflectiveOperationException {
            inject(displayId, position, downTime, action,
                    MotionEvent.TOOL_TYPE_FINGER,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                    0,
                    pressure);
        }

        private void inject(
                final int displayId,
                final Point position,
                final long downTime,
                final int action,
                final int toolType,
                final int source,
                final int buttonState,
                final int actionButton,
                final float pressure)
                throws ReflectiveOperationException {
            final MotionEvent.PointerProperties properties =
                    new MotionEvent.PointerProperties();
            properties.id = 0;
            properties.toolType = toolType;
            final MotionEvent.PointerCoords coordinates =
                    new MotionEvent.PointerCoords();
            coordinates.x = position.x;
            coordinates.y = position.y;
            coordinates.pressure = pressure;
            coordinates.size = 1.0f;
            final MotionEvent event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action, 1,
                    new MotionEvent.PointerProperties[] {properties},
                    new MotionEvent.PointerCoords[] {coordinates},
                    0, buttonState, 1.0f, 1.0f, 0, 0, source, 0);
            try {
                mSetActionButton.invoke(
                        event, Integer.valueOf(actionButton));
                mSetDisplayId.invoke(event, Integer.valueOf(displayId));
                final Object result = mInject.getParameterCount() == 2
                        ? mInject.invoke(mInputManager, event,
                                Integer.valueOf(
                                        INJECTION_MODE_WAIT_FOR_RESULT))
                        : mInject.invoke(mInputManager, event,
                                Integer.valueOf(
                                        INJECTION_MODE_WAIT_FOR_RESULT),
                                Integer.valueOf(-1));
                if (result instanceof Boolean
                        && !((Boolean) result).booleanValue()) {
                    throw new IllegalStateException(
                            "input injection was rejected");
                }
            } finally {
                event.recycle();
            }
        }
    }

    private static Object getInputManager()
            throws ReflectiveOperationException {
        final Class<?> serviceManager = Class.forName(
                "android.os.ServiceManager");
        final IBinder binder = (IBinder) serviceManager
                .getMethod("getService", String.class)
                .invoke(null, "input");
        return Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }

    private static Method findInjectMethod()
            throws ReflectiveOperationException {
        final Class<?> type = Class.forName(
                "android.hardware.input.IInputManager");
        try {
            return type.getMethod(
                    "injectInputEvent", InputEvent.class, Integer.TYPE);
        } catch (NoSuchMethodException ignored) {
            return type.getMethod(
                    "injectInputEventToTarget",
                    InputEvent.class,
                    Integer.TYPE,
                    Integer.TYPE);
        }
    }
}
