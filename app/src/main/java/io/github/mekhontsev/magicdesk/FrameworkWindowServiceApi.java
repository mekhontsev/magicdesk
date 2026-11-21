package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

/** Window-service IPC without initializing an application window/animation runtime. */
final class FrameworkWindowServiceApi {
    private static Object sWindowManager;

    private FrameworkWindowServiceApi() { }

    static synchronized Object get() throws ReflectiveOperationException {
        if (sWindowManager == null) {
            // WindowManagerGlobal also initializes ValueAnimator from ApplicationSharedMemory.
            // Standalone UserService/app_process instances do not receive bindApplication's FD.
            final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "window");
            if (binder == null) throw new IllegalStateException("WindowManager service is unavailable");
            final Object service = Class.forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (service == null) throw new IllegalStateException("WindowManager interface is unavailable");
            sWindowManager = service;
        }
        return sWindowManager;
    }
}
