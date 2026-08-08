package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

import java.lang.reflect.Method;

/** Forwards phone IME operations to Nubia's focused mirrored window. */
final class DesktopMirrorTextInput {
    static final int COMMIT_TEXT = 1;
    static final int SEND_KEY = 2;
    static final int SET_COMPOSING_TEXT = 3;
    static final int SET_COMPOSING_REGION = 4;
    static final int FINISH_COMPOSING = 5;
    static final int DELETE_SURROUNDING = 6;

    private static volatile Access sAccess;

    private DesktopMirrorTextInput() {
    }

    static Session capture() throws ReflectiveOperationException {
        final Access access = access();
        final Object window = access.getFocusMirrorWindow.invoke(
                access.displayManager);
        return window == null ? null : new Session(access, window);
    }

    private static String safeText(final String text) {
        return text == null ? "" : text;
    }

    static final class Session {
        private final Access mAccess;
        private final Object mWindow;

        Session(final Access access, final Object window) {
            mAccess = access;
            mWindow = window;
        }

        boolean dispatch(
                final int action,
                final String text,
                final int arg1,
                final int arg2,
                final int arg3) throws ReflectiveOperationException {
            switch (action) {
                case COMMIT_TEXT:
                    mAccess.dispatchText.invoke(mWindow, safeText(text));
                    return true;
                case SEND_KEY:
                    mAccess.dispatchKeyEvent.invoke(
                            mWindow,
                            Integer.valueOf(arg1),
                            Integer.valueOf(arg2),
                            Integer.valueOf(arg3));
                    return true;
                case SET_COMPOSING_TEXT:
                    mAccess.setComposingText.invoke(
                            mWindow, safeText(text), Integer.valueOf(arg1));
                    return true;
                case SET_COMPOSING_REGION:
                    mAccess.setComposingRegion.invoke(
                            mWindow,
                            Integer.valueOf(arg1),
                            Integer.valueOf(arg2));
                    return true;
                case FINISH_COMPOSING:
                    mAccess.finishComposingText.invoke(mWindow);
                    return true;
                case DELETE_SURROUNDING:
                    mAccess.deleteSurroundingText.invoke(
                            mWindow,
                            Integer.valueOf(arg1),
                            Integer.valueOf(arg2));
                    return true;
                default:
                    throw new IllegalArgumentException(
                            "unknown mirror text input action: " + action);
            }
        }
    }

    private static Access access() throws ReflectiveOperationException {
        Access access = sAccess;
        if (access != null) {
            return access;
        }
        synchronized (DesktopMirrorTextInput.class) {
            access = sAccess;
            if (access == null) {
                access = new Access();
                sAccess = access;
            }
        }
        return access;
    }

    private static final class Access {
        final Object displayManager;
        final Method getFocusMirrorWindow;
        final Method dispatchText;
        final Method dispatchKeyEvent;
        final Method setComposingText;
        final Method setComposingRegion;
        final Method finishComposingText;
        final Method deleteSurroundingText;

        Access() throws ReflectiveOperationException {
            final Class<?> serviceManager = Class.forName(
                    "android.os.ServiceManager");
            final IBinder binder = (IBinder) serviceManager
                    .getMethod("getService", String.class)
                    .invoke(null, "display");
            final Class<?> displayManagerType = Class.forName(
                    "android.hardware.display.IDisplayManager");
            displayManager = Class.forName(
                    "android.hardware.display.IDisplayManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            getFocusMirrorWindow = displayManagerType.getMethod(
                    "getFocusMirrorWindow");

            final Class<?> mirrorWindow = Class.forName(
                    "android.view.IDisplayMirrorWindow");
            dispatchText = mirrorWindow.getMethod(
                    "dispatchText", String.class);
            dispatchKeyEvent = mirrorWindow.getMethod(
                    "dispatchKeyEvent",
                    int.class, int.class, int.class);
            setComposingText = mirrorWindow.getMethod(
                    "setComposingText", String.class, int.class);
            setComposingRegion = mirrorWindow.getMethod(
                    "setComposingRegion", int.class, int.class);
            finishComposingText = mirrorWindow.getMethod(
                    "finishComposingText");
            deleteSurroundingText = mirrorWindow.getMethod(
                    "deleteSurroundingText", int.class, int.class);
        }
    }
}
