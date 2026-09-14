package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

import java.lang.reflect.Method;

/** Android's shell-authorized IME dismissal, independent of editor and window focus. */
final class FrameworkInputMethodApi {
    private final Object mStatusBar;
    private final Method mHideCurrentInputMethod;

    FrameworkInputMethodApi() throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "statusbar");
        if (binder == null) {
            throw new IllegalStateException("Android status bar service is unavailable");
        }
        mStatusBar = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        mHideCurrentInputMethod = Class.forName(
                "com.android.internal.statusbar.IStatusBarService")
                .getMethod("hideCurrentInputMethodForBubbles", int.class);
    }

    void hideCurrentInputMethod(final int originatingDisplayId)
            throws ReflectiveOperationException {
        if (originatingDisplayId < 0) {
            throw new IllegalArgumentException("originating display is required");
        }
        // The display supplies user context, not a filter for the current editor's display.
        mHideCurrentInputMethod.invoke(mStatusBar, originatingDisplayId);
    }
}
