package io.github.mekhontsev.magicdesk.x11;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Process;
import android.os.UserHandle;

/** Only the app_process context adapter touches hidden framework startup methods. */
final class X11ProcessContext {
    private X11ProcessContext() { }

    @SuppressLint("PrivateApi")
    static Context create(String executorPackage) throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
        Context context = (Context) Context.class.getMethod("createPackageContextAsUser",
                String.class, int.class, UserHandle.class).invoke(system, executorPackage,
                Context.CONTEXT_IGNORE_SECURITY, UserHandle.getUserHandleForUid(Process.myUid()));
        if (context.getApplicationInfo().uid != Process.myUid())
            throw new SecurityException("X11 executor package does not match process UID");
        return context;
    }
}
