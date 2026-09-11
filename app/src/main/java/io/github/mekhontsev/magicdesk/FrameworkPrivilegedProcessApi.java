package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;

/** App context for an app_process, without starting the APK's application/runtime. */
final class FrameworkPrivilegedProcessApi {
    private FrameworkPrivilegedProcessApi() { }

    static Bundle attachService(int userId, String token, Bundle request) throws ReflectiveOperationException {
        final String authority = ShellServiceProvider.AUTHORITY;
        final Class<?> managerApi = Class.forName("android.app.IActivityManager");
        final Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        final Binder reference = new Binder();
        // An app_process has no AMS application record. Use the same acquisition boundary as cmd content.
        final Object holder = managerApi.getMethod("getContentProviderExternal", String.class,
                int.class, IBinder.class, String.class).invoke(manager, authority, userId, reference, "MagicDesk service");
        if (holder == null) throw new IllegalStateException("MagicDesk service provider unavailable");
        try {
            final Object provider = holder.getClass().getField("provider").get(holder);
            final AttributionSource caller = new AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName(android.os.Process.myUid() == 2000 ? "com.android.shell" : "root").build();
            return (Bundle) Class.forName("android.content.IContentProvider")
                    .getMethod("call", AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                    .invoke(provider, caller, authority, "attach", token, request);
        } finally {
            managerApi.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(manager, authority, reference, userId);
        }
    }

    static Context createContext(int userId) throws ReflectiveOperationException {
        if (userId < 0) throw new IllegalArgumentException("invalid Android user");
        final Class<?> threadClass = Class.forName("android.app.ActivityThread");
        final Object thread = threadClass.getMethod("systemMain").invoke(null);
        final Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        final UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
        final Context context = (Context) Context.class.getMethod("createPackageContextAsUser",
                String.class, int.class, UserHandle.class).invoke(system, BuildConfig.APPLICATION_ID,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, user);
        final var packageInfo = context.getClass().getDeclaredField("mPackageInfo");
        packageInfo.setAccessible(true);
        final Object loadedApk = packageInfo.get(context);
        final var makeApplication = loadedApk.getClass().getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
        makeApplication.setAccessible(true);
        // forceDefaultAppClass avoids MagicDeskApplication.onCreate and its HOME recovery.
        final Application app = (Application) makeApplication.invoke(loadedApk, true, null);
        final var initialApp = threadClass.getDeclaredField("mInitialApplication");
        initialApp.setAccessible(true);
        initialApp.set(thread, app);
        return app;
    }
}
