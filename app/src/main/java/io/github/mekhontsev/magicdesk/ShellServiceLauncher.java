package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.ServiceConnection;

/** Only process creation, authorization and Binder delivery differ between launchers. */
interface ShellServiceLauncher {
    enum Service {
        COMMAND(ShellCommandService.class, "command", false),
        UPDATE(ShellAppUpdateService.class, "app_update", true);

        final Class<?> implementation;
        final String processName;
        final boolean independent;

        Service(Class<?> implementation, String processName, boolean independent) {
            this.implementation = implementation;
            this.processName = processName;
            this.independent = independent;
        }
    }

    interface Binding {
        /** Accepted update work is detached without terminating its process. */
        void close(boolean terminate);
    }

    void initialize(Runnable changed, Runnable disconnected);
    ShellAccess.Snapshot inspect();
    boolean canBind();
    default long bindTimeoutMillis() { return 10_000; }
    Binding bind(Service service, String tag, ServiceConnection connection);
    void requestPermission();
    void openManager(Context context);

    static ShellServiceLauncher current() { return Active.INSTANCE; }

    final class Active {
        static final ShellServiceLauncher INSTANCE = ShellBackend.active().usesRoot()
                ? new ShellProcessLauncher(ShellBackend.active()) : new ShizukuServiceLauncher();
        private Active() { }
    }
}
