package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformTextInputDriver;

import android.os.IBinder;

import java.lang.reflect.Method;

/** Forwards phone IME operations to Nubia's focused mirrored window. */
final class NubiaMirrorTextInputDriver implements PlatformTextInputDriver {
    static final NubiaMirrorTextInputDriver INSTANCE =
            new NubiaMirrorTextInputDriver();

    private static volatile Access sAccess;
    private static volatile RuntimeState sRuntimeState =
            new RuntimeState("not_tested", "no keyboard session requested");

    private NubiaMirrorTextInputDriver() {
    }

    @Override
    public PlatformTextInputDriver.Session capture()
            throws ReflectiveOperationException {
        try {
            final Access access = access();
            final Object window = access.api.getFocusMirrorWindow.invoke(
                    access.displayManager);
            if (window == null) {
                sRuntimeState = new RuntimeState(
                        "no_focused_window",
                        "no focused projected window during the last request");
                return null;
            }
            sRuntimeState = new RuntimeState(
                    "not_tested",
                    "focused mirror window captured; no text operation sent");
            return new NubiaSession(access, window);
        } catch (ReflectiveOperationException | RuntimeException error) {
            recordFailure(error);
            throw error;
        }
    }

    @Override
    public void verifyApi() throws ReflectiveOperationException {
        new Api();
    }

    @Override
    public RuntimeState runtimeState() {
        return sRuntimeState;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private static String safeText(final String text) {
        return text == null ? "" : text;
    }

    private static final class NubiaSession
            implements PlatformTextInputDriver.Session {
        private final Access mAccess;
        private final Object mWindow;

        NubiaSession(final Access access, final Object window) {
            mAccess = access;
            mWindow = window;
        }

        @Override
        public boolean dispatch(
                final int action,
                final String text,
                final int arg1,
                final int arg2,
                final int arg3) throws ReflectiveOperationException {
            try {
                switch (action) {
                    case PlatformTextInputDriver.COMMIT_TEXT:
                        mAccess.api.dispatchText.invoke(mWindow, safeText(text));
                        break;
                    case PlatformTextInputDriver.SEND_KEY:
                        mAccess.api.dispatchKeyEvent.invoke(
                                mWindow,
                                Integer.valueOf(arg1),
                                Integer.valueOf(arg2),
                                Integer.valueOf(arg3));
                        break;
                    case PlatformTextInputDriver.SET_COMPOSING_TEXT:
                        mAccess.api.setComposingText.invoke(
                                mWindow, safeText(text), Integer.valueOf(arg1));
                        break;
                    case PlatformTextInputDriver.SET_COMPOSING_REGION:
                        mAccess.api.setComposingRegion.invoke(
                                mWindow,
                                Integer.valueOf(arg1),
                                Integer.valueOf(arg2));
                        break;
                    case PlatformTextInputDriver.FINISH_COMPOSING:
                        mAccess.api.finishComposingText.invoke(mWindow);
                        break;
                    case PlatformTextInputDriver.DELETE_SURROUNDING:
                        mAccess.api.deleteSurroundingText.invoke(
                                mWindow,
                                Integer.valueOf(arg1),
                                Integer.valueOf(arg2));
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "unknown mirror text input action: " + action);
                }
                recordWorking();
                return true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                recordFailure(error);
                throw error;
            }
        }
    }

    private static Access access() throws ReflectiveOperationException {
        Access access = sAccess;
        if (access != null) {
            return access;
        }
        synchronized (NubiaMirrorTextInputDriver.class) {
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
        final Api api;

        Access() throws ReflectiveOperationException {
            api = new Api();
            final Class<?> serviceManager = Class.forName(
                    "android.os.ServiceManager");
            final IBinder binder = (IBinder) serviceManager
                    .getMethod("getService", String.class)
                    .invoke(null, "display");
            if (binder == null) {
                throw new IllegalStateException("display service is unavailable");
            }
            displayManager = Class.forName(
                    "android.hardware.display.IDisplayManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            if (displayManager == null) {
                throw new IllegalStateException(
                        "display manager interface is unavailable");
            }
        }
    }

    private static final class Api {
        final Method getFocusMirrorWindow;
        final Method dispatchText;
        final Method dispatchKeyEvent;
        final Method setComposingText;
        final Method setComposingRegion;
        final Method finishComposingText;
        final Method deleteSurroundingText;

        Api() throws ReflectiveOperationException {
            final Class<?> displayManagerType = Class.forName(
                    "android.hardware.display.IDisplayManager");
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

    private static void recordFailure(final Throwable error) {
        final String message = error.getMessage();
        sRuntimeState = new RuntimeState(
                "failed",
                error.getClass().getSimpleName()
                        + (message == null || message.isEmpty()
                                ? "" : ": " + message));
    }

    private static void recordWorking() {
        if (!"working".equals(sRuntimeState.state)) {
            sRuntimeState = new RuntimeState(
                    "working", "mirror text operation completed");
        }
    }
}
