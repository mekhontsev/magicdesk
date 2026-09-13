package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Display-addressed injection shared by tests and an explicitly controlled viewer. */
final class FrameworkInputInjectionApi {
    private final Object mManager;
    private final Method mInject;
    // Only instantiated in the authorized app_process service, never the app sandbox.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private final Method mDisplayId = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
    private final Method mActionButton = MotionEvent.class.getMethod("setActionButton", int.class);

    FrameworkInputInjectionApi() throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        mManager = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        mInject = injectionMethod();
        mDisplayId.setAccessible(true);
    }

    private static Method injectionMethod() throws ReflectiveOperationException {
        final Class<?> type = Class.forName("android.hardware.input.IInputManager");
        try { return type.getMethod("injectInputEvent", InputEvent.class, int.class); }
        catch (NoSuchMethodException unavailable) {
            return type.getMethod("injectInputEventToTarget", InputEvent.class, int.class, int.class);
        }
    }

    void actionButton(MotionEvent event, int button) throws ReflectiveOperationException {
        mActionButton.invoke(event, button);
    }

    void inject(int displayId, InputEvent event, int mode) throws ReflectiveOperationException {
        if (displayId < 0) { throw new IllegalArgumentException("missing target display"); }
        mDisplayId.invoke(event, displayId);
        final Object result = mInject.getParameterCount() == 2
                ? mInject.invoke(mManager, event, mode) : mInject.invoke(mManager, event, mode, -1);
        if (!Boolean.TRUE.equals(result)) { throw new IllegalStateException("input injection was rejected"); }
    }
}
