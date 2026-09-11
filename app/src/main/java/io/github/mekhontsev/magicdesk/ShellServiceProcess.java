package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/** The app_process entry point loads one owned service, without starting the application runtime. */
public final class ShellServiceProcess {
    private ShellServiceProcess() { }

    public static void main(String[] args) {
        try {
            if (args.length != 4) throw new SecurityException("invalid root startup");
            ShellServiceStartup.verifyIdentity(Integer.parseInt(args[3]));
            final ShellServiceLauncher.Service kind = ShellServiceLauncher.Service.valueOf(args[0]);
            Looper.prepareMainLooper();
            final Context context = FrameworkPrivilegedProcessApi.createContext(Integer.parseInt(args[2]));
            final Binder service;
            final Runnable destroy;
            if (kind == ShellServiceLauncher.Service.COMMAND) {
                final var command = new ShellCommandService(context);
                service = command;
                destroy = command::destroy;
            } else {
                final var update = new ShellAppUpdateService(context);
                service = update;
                destroy = update::destroy;
            }
            final Bundle request = new Bundle();
            request.putString("sourceId", BuildConfig.SOURCE_ID);
            request.putBinder("service", new Endpoint(service, context.getApplicationInfo().uid));
            final Bundle response = FrameworkPrivilegedProcessApi.attachService(Integer.parseInt(args[2]), args[1], request);
            final IBinder owner = response == null ? null : response.getBinder("owner");
            if (owner == null) throw new SecurityException("root handoff was not accepted");
            if (!kind.independent) {
                // The normal service dies with its APK. The accepted updater has its own lifetime.
                owner.linkToDeath(destroy::run, 0);
            }
            Looper.loop();
        } catch (Throwable error) {
            Log.e("MagicDeskRoot", "Root service startup failed", error);
        }
        System.exit(1);
    }

    private static final class Endpoint extends Binder {
        private final Binder mService;
        private final int mClientUid;

        Endpoint(Binder service, int clientUid) {
            mService = service;
            mClientUid = clientUid;
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (Binder.getCallingUid() != mClientUid) throw new SecurityException("foreign root service client");
            return mService.transact(code, data, reply, flags);
        }
    }
}
