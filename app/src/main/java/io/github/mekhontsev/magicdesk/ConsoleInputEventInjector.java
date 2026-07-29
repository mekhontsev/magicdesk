package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.view.InputEvent;

import java.lang.reflect.Method;

final class ConsoleInputEventInjector {
    private static final int MODE_WAIT_FOR_RESULT = 1;
    private static final int INVALID_UID = -1;

    private final Object mInputManager;
    private final Method mInjectInputEvent;
    private final Method mSetInputEventDisplayId;
    private final int mDisplayId;

    @SuppressLint("BlockedPrivateApi")
    ConsoleInputEventInjector(
            final Object inputManager,
            final Class<?> inputManagerInterface,
            final int displayId) throws ReflectiveOperationException {
        mInputManager = inputManager;
        mInjectInputEvent = findInjectInputEventMethod(inputManagerInterface);
        mSetInputEventDisplayId =
                InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
        mSetInputEventDisplayId.setAccessible(true);
        mDisplayId = displayId;
    }

    void targetDisplay(final InputEvent event)
            throws ReflectiveOperationException {
        mSetInputEventDisplayId.invoke(event, Integer.valueOf(mDisplayId));
    }

    boolean inject(final InputEvent event) throws ReflectiveOperationException {
        final Object result;
        if (mInjectInputEvent.getParameterTypes().length == 2) {
            result = mInjectInputEvent.invoke(
                    mInputManager,
                    event,
                    Integer.valueOf(MODE_WAIT_FOR_RESULT));
        } else {
            result = mInjectInputEvent.invoke(
                    mInputManager,
                    event,
                    Integer.valueOf(MODE_WAIT_FOR_RESULT),
                    Integer.valueOf(INVALID_UID));
        }
        return !(result instanceof Boolean)
                || ((Boolean) result).booleanValue();
    }

    private static Method findInjectInputEventMethod(
            final Class<?> inputManagerInterface)
            throws NoSuchMethodException {
        try {
            return inputManagerInterface.getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
        } catch (NoSuchMethodException ignored) {
            try {
                return inputManagerInterface.getMethod(
                        "injectInputEventToTarget",
                        InputEvent.class,
                        int.class,
                        int.class);
            } catch (NoSuchMethodException ignoredAgain) {
                return inputManagerInterface.getMethod(
                        "injectInputEvent",
                        InputEvent.class,
                        int.class,
                        int.class);
            }
        }
    }
}
