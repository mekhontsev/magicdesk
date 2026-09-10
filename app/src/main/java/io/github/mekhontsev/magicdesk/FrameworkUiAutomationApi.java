package io.github.mekhontsev.magicdesk;

import android.app.UiAutomation;
import android.content.Context;
import android.os.Looper;
import android.view.InputEvent;
import java.lang.reflect.InvocationTargetException;

/** Shell-only entry to Android's automation connection; public APIs own the UI work. */
final class FrameworkUiAutomationApi {
    private FrameworkUiAutomationApi() { }

    static UiAutomation connect(final Context context) {
        UiAutomation automation = null;
        try {
            final Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            final Object connection = Class.forName("android.app.UiAutomationConnection")
                    .getConstructor().newInstance();
            automation = context != null
                    ? (UiAutomation) UiAutomation.class.getConstructor(Context.class, connectionType)
                            .newInstance(context, connection)
                    : (UiAutomation) UiAutomation.class.getConstructor(Looper.class, connectionType)
                            .newInstance(Looper.getMainLooper(), connection);
            EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.UI_AUTOMATION_CONNECTION);
            UiAutomation.class.getMethod("connectWithTimeout", int.class, long.class).invoke(
                    automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, 5000L);
            return automation;
        } catch (ReflectiveOperationException error) {
            if (automation != null) {
                try { disconnect(automation); }
                catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            }
            final Throwable cause = error instanceof InvocationTargetException
                    ? ((InvocationTargetException) error).getTargetException() : error;
            throw new IllegalStateException("Android UI automation unavailable; another automation client "
                    + "may already own the connection: " + cause.getMessage(), cause);
        }
    }

    static void disconnect(final UiAutomation automation) {
        try {
            UiAutomation.class.getMethod("destroy").invoke(automation);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot release Android UI automation", error);
        }
    }

    static void target(final InputEvent event, final int displayId) {
        try {
            InputEvent.class.getMethod("setDisplayId", int.class).invoke(event, displayId);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot target input display", error);
        }
    }

    static void inject(final UiAutomation automation, final InputEvent event, final int displayId) {
        target(event, displayId);
        try {
            // A gesture must not pause for each window animation. Completion is observed separately.
            EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.UI_AUTOMATION_INPUT);
            final boolean accepted = (Boolean) UiAutomation.class.getMethod("injectInputEvent",
                    InputEvent.class, boolean.class, boolean.class).invoke(automation, event, true, false);
            if (!accepted) throw new IllegalStateException("Android rejected input injection");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Android input injection failed", error);
        }
    }
}
