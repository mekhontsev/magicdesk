package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Point;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/** Injects display-targeted pointer actions. */
public final class DesktopPointerInjector {
    private static final int INJECTION_MODE_WAIT_FOR_RESULT = 1;

    private static volatile InjectionContext sInjectionContext;

    private DesktopPointerInjector() {
    }

    @SuppressLint("BlockedPrivateApi")
    public static void injectClickAt(
            final int displayId,
            final Point position,
            final int button) {
        validateDisplay(displayId);
        if (button != MotionEvent.BUTTON_PRIMARY
                && button != MotionEvent.BUTTON_SECONDARY) {
            throw new IllegalArgumentException(
                    "unsupported pointer button: " + button);
        }
        try {
            final InjectionContext context = injectionContext();
            final long downTime = SystemClock.uptimeMillis();
            context.injectMouse(displayId, position, downTime,
                    MotionEvent.ACTION_DOWN,
                    button, 0);
            context.injectMouse(displayId, position, downTime,
                    MotionEvent.ACTION_BUTTON_PRESS,
                    button, button);
            context.injectMouse(displayId, position, downTime,
                    MotionEvent.ACTION_BUTTON_RELEASE,
                    0, button);
            context.injectMouse(displayId, position, downTime,
                    MotionEvent.ACTION_UP, 0, 0);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "could not inject pointer click", error);
        }
    }

    @SuppressLint("BlockedPrivateApi")
    static void injectMouseHover(
            final int displayId,
            final Point position) throws ReflectiveOperationException {
        validateDisplay(displayId);
        injectionContext().injectMouseHover(displayId, position);
    }

    @SuppressLint("BlockedPrivateApi")
    static void injectSyntheticTouchLongPress(
            final int displayId,
            final Point position,
            final long durationMillis) throws ReflectiveOperationException {
        validateDisplay(displayId);
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("negative press duration");
        }
        final InjectionContext context = injectionContext();
        final long downTime = SystemClock.uptimeMillis();
        boolean pressed = false;
        try {
            context.injectSyntheticTouch(displayId, position, downTime,
                    MotionEvent.ACTION_DOWN, 1.0f);
            pressed = true;
            RuntimeDelays.pause(
                    RuntimeDelays.Reason.INPUT_GESTURE, durationMillis);
        } finally {
            if (pressed) {
                context.injectSyntheticTouch(displayId, position, downTime,
                        MotionEvent.ACTION_UP, 0.0f);
            }
        }
    }

    @SuppressLint("BlockedPrivateApi")
    static void injectMouseDrag(
            final int displayId,
            final Point start,
            final Point end,
            final long durationMillis) throws ReflectiveOperationException {
        validateDisplay(displayId);
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("negative drag duration");
        }
        final InjectionContext context = injectionContext();
        final long downTime = SystemClock.uptimeMillis();
        boolean dragStarted = false;
        try {
            context.injectMouse(displayId, start, downTime,
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.BUTTON_PRIMARY, 0);
            dragStarted = true;
            context.injectMouse(displayId, start, downTime,
                    MotionEvent.ACTION_BUTTON_PRESS,
                    MotionEvent.BUTTON_PRIMARY,
                    MotionEvent.BUTTON_PRIMARY);
            final int steps = 8;
            for (int step = 1; step <= steps; step++) {
                if (durationMillis > 0L) {
                    RuntimeDelays.pause(
                            RuntimeDelays.Reason.INPUT_GESTURE,
                            durationMillis / steps);
                }
                context.injectMouse(displayId,
                        interpolate(start, end, step, steps),
                        downTime, MotionEvent.ACTION_MOVE,
                        MotionEvent.BUTTON_PRIMARY, 0);
            }
        } finally {
            if (dragStarted) {
                try {
                    context.injectMouse(displayId, end, downTime,
                            MotionEvent.ACTION_BUTTON_RELEASE,
                            0, MotionEvent.BUTTON_PRIMARY);
                } finally {
                    context.injectMouse(displayId, end, downTime,
                            MotionEvent.ACTION_UP, 0, 0);
                }
            }
        }
    }

    private static Point interpolate(
            final Point start,
            final Point end,
            final int step,
            final int steps) {
        return new Point(
                start.x + (end.x - start.x) * step / steps,
                start.y + (end.y - start.y) * step / steps);
    }

    private static void validateDisplay(final int displayId) {
        if (displayId < 0) {
            throw new IllegalArgumentException("missing target display");
        }
    }

    private static InjectionContext injectionContext()
            throws ReflectiveOperationException {
        InjectionContext context = sInjectionContext;
        if (context != null) {
            return context;
        }
        synchronized (DesktopPointerInjector.class) {
            context = sInjectionContext;
            if (context == null) {
                context = new InjectionContext();
                sInjectionContext = context;
            }
        }
        return context;
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
                    0.0f,
                    INJECTION_MODE_WAIT_FOR_RESULT,
                    inputDeviceId(InputDevice.SOURCE_MOUSE),
                    1.0f);
        }


        void injectSyntheticTouch(
                final int displayId,
                final Point position,
                final long downTime,
                final int action,
                final float pressure) throws ReflectiveOperationException {
            // Match Android's display-targeted input command. A physical
            // touchscreen id remains associated with display 0 on Nubia.
            inject(displayId, position, downTime, action,
                    MotionEvent.TOOL_TYPE_FINGER,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                    0,
                    pressure,
                    INJECTION_MODE_WAIT_FOR_RESULT,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,
                    1.0f);
        }

        void injectMouseHover(
                final int displayId,
                final Point position) throws ReflectiveOperationException {
            final long eventTime = SystemClock.uptimeMillis();
            inject(displayId, position, eventTime,
                    MotionEvent.ACTION_HOVER_MOVE,
                    MotionEvent.TOOL_TYPE_MOUSE,
                    InputDevice.SOURCE_MOUSE,
                    0,
                    0,
                    0.0f,
                    INJECTION_MODE_WAIT_FOR_RESULT,
                    inputDeviceId(InputDevice.SOURCE_MOUSE),
                    1.0f);
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
                final float pressure,
                final int injectionMode,
                final int deviceId,
                final float precision)
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
                    0, buttonState, precision, precision,
                    deviceId, 0, source, 0);
            try {
                mSetActionButton.invoke(
                        event, Integer.valueOf(actionButton));
                injectEvent(displayId, event, injectionMode);
            } finally {
                event.recycle();
            }
        }

        private void injectEvent(
                final int displayId,
                final InputEvent event,
                final int injectionMode)
                throws ReflectiveOperationException {
            mSetDisplayId.invoke(event, Integer.valueOf(displayId));
            final Object result = mInject.getParameterCount() == 2
                    ? mInject.invoke(mInputManager, event,
                            Integer.valueOf(injectionMode))
                    : mInject.invoke(mInputManager, event,
                            Integer.valueOf(injectionMode),
                            Integer.valueOf(-1));
            if (result instanceof Boolean
                    && !((Boolean) result).booleanValue()) {
                throw new IllegalStateException(
                        "input injection was rejected");
            }
        }
    }

    private static int inputDeviceId(final int source) {
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && device.supportsSource(source)) {
                return deviceId;
            }
        }
        return 0;
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
