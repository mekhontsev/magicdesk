package io.github.mekhontsev.magicdesk.x11;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/** Isolated app_process under the selected executor UID, never the host's privileged service. */
public final class X11Server extends IX11Server.Stub {
    public static final String ACTION = "io.github.mekhontsev.magicdesk.x11.SERVER_READY";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final X11FileExchange files = new X11FileExchange();
    private final Context context;
    private final String hostPackage, session, token;
    private final int hostUid;
    private final String[] arguments;
    private final X11ServerLifecycle lifecycle;
    private IBinder owner;
    private final IBinder.DeathRecipient ownerDied = this::requestStop;
    private final Runnable deadline = this::expireAdmission;

    private X11Server(String[] arguments) throws Exception {
        this.arguments = arguments.clone();
        hostPackage = required("MAGICDESK_X11_PACKAGE");
        session = required("MAGICDESK_X11_SESSION");
        token = required("MAGICDESK_X11_TOKEN");
        context = X11ProcessContext.create(required("MAGICDESK_X11_EXECUTOR"));
        hostUid = context.getPackageManager().getPackageUid(hostPackage, 0);
        lifecycle = new X11ServerLifecycle(hostUid);
        System.load(required("MAGICDESK_X11_LIBRARY"));
    }

    public static void main(String[] arguments) throws Exception {
        Looper.prepareMainLooper();
        X11Server server = new X11Server(arguments);
        server.handler.postDelayed(server.deadline, 60_000);
        server.announce("attach", null);
        Looper.loop();
    }

    private void expireAdmission() { if (!lifecycle.retained()) System.exit(1); }

    @Override public synchronized void retain(IBinder binder) throws RemoteException {
        lifecycle.checkCaller(Binder.getCallingUid());
        if (owner != null) {
            if (!owner.equals(binder)) throw new SecurityException("X11 server already owned");
            return;
        }
        java.util.Objects.requireNonNull(binder).linkToDeath(ownerDied, 0);
        owner = binder;
        lifecycle.retain();
        handler.removeCallbacks(deadline);
        handler.post(() -> {
            if (!lifecycle.beginStart()) return;
            if (!nativeStart(arguments)) System.exit(1);
        });
    }

    @Override public ParcelFileDescriptor openConnection() {
        lifecycle.checkReady(Binder.getCallingUid());
        int fd = nativeConnect();
        if (fd < 0) throw new IllegalStateException("Cannot open X11 connection");
        return ParcelFileDescriptor.adoptFd(fd);
    }

    @Override public void stop() {
        lifecycle.checkCaller(Binder.getCallingUid());
        requestStop();
    }

    private void requestStop() {
        files.close();
        handler.post(() -> {
            X11ServerLifecycle.Stop action = lifecycle.stop();
            if (action == X11ServerLifecycle.Stop.EXIT) System.exit(0);
            if (action == X11ServerLifecycle.Stop.NATIVE) nativeStop();
        });
    }

    // Called once at ddxReady, after display allocation and input initialization.
    private void onNativeReady(String display) {
        handler.post(() -> {
            if (lifecycle.ready()) announce("ready", display);
            else nativeStop();
        });
    }

    @Override public ParcelFileDescriptor openContentFile(String uri) {
        lifecycle.checkReady(Binder.getCallingUid());
        try { return files.open(uri); }
        catch (java.io.IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
    }

    @Override public String importContentFile(ParcelFileDescriptor source, String name) {
        try {
            lifecycle.checkReady(Binder.getCallingUid());
            return files.importFile(source, name);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        } catch (RuntimeException error) {
            if (source != null) try { source.close(); } catch (java.io.IOException ignored) { }
            throw error;
        }
    }

    private void announce(String phase, String display) {
        android.os.Bundle extras = new android.os.Bundle();
        extras.putBinder("server", this);
        extras.putString("session", session);
        extras.putString("token", token);
        extras.putString("phase", phase);
        extras.putString("display", display);
        context.sendBroadcast(new Intent(ACTION).setPackage(hostPackage).putExtras(extras), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private native boolean nativeStart(String[] arguments);
    private static native int nativeConnect();
    private static native void nativeStop();
}
