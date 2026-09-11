package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.IOException;

import rikka.shizuku.Shizuku;

final class ShizukuServiceLauncher implements ShellServiceLauncher {
    private static final int REQUEST_PERMISSION = 7104;
    private ShellProcessLauncher mRestricted;

    @Override public void initialize(Runnable changed, Runnable disconnected) {
        Shizuku.addBinderReceivedListenerSticky(disconnected::run);
        Shizuku.addBinderDeadListener(disconnected::run);
        Shizuku.addRequestPermissionResultListener((request, grant) -> {
            if (request == REQUEST_PERMISSION) changed.run();
        });
    }

    @Override public ShellAccess.Snapshot inspect() {
        final Context context = MagicDeskApplication.applicationContext();
        boolean installed;
        try {
            context.getPackageManager().getPackageInfo(IntegrationPackage.SHIZUKU.selected(),
                    PackageManager.PackageInfoFlags.of(0));
            installed = true;
        } catch (PackageManager.NameNotFoundException error) { installed = false; }
        try {
            if (!Shizuku.pingBinder()) {
                return ShellAccess.Snapshot.unavailable(ShellBackend.SHIZUKU, installed,
                        "Shizuku API unavailable; selected manager: " + IntegrationPackage.SHIZUKU.selected());
            }
            final int version = Shizuku.getVersion();
            if (version < 11) {
                return new ShellAccess.Snapshot(ShellBackend.SHIZUKU, installed, true, false,
                        -1, version, "Shizuku API 11 or newer is required");
            }
            final int uid = Shizuku.getUid();
            final boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            return new ShellAccess.Snapshot(ShellBackend.SHIZUKU, installed, true, granted, uid, version,
                    !granted ? "Shizuku permission is not granted"
                            : !ShellAccess.isSupportedServiceUid(uid) ? "Unsupported service UID: " + uid : "");
        } catch (RuntimeException error) {
            return ShellAccess.Snapshot.unavailable(ShellBackend.SHIZUKU, installed, ShellAccess.usefulMessage(error));
        }
    }

    @Override public boolean canBind() { return inspect().isReady(); }

    private synchronized ShellProcessLauncher restrictedLauncher() {
        if (mRestricted == null) mRestricted = new ShellProcessLauncher(ShellBackend.SHIZUKU);
        return mRestricted;
    }

    @Override public long bindTimeoutMillis() {
        return ShellPrivilegePolicy.forceShell() && Shizuku.getUid() == 0
                ? restrictedLauncher().bindTimeoutMillis() : ShellServiceLauncher.super.bindTimeoutMillis();
    }

    @Override public Binding bind(Service service, String tag, ServiceConnection connection) {
        if (ShellPrivilegePolicy.forceShell() && Shizuku.getUid() == 0) {
            return restrictedLauncher().bind(service, tag, connection);
        }
        final Context context = MagicDeskApplication.applicationContext();
        final var args = new Shizuku.UserServiceArgs(new ComponentName(context, service.implementation))
                .daemon(service.independent).processNameSuffix(service.processName).tag(tag)
                .debuggable((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                .version(BuildConfig.SOURCE_ID.hashCode() & Integer.MAX_VALUE);
        Shizuku.bindUserService(args, connection);
        return terminate -> {
            if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, connection, terminate);
        };
    }

    @Override public void requestPermission() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) ShellAccess.refresh();
        else Shizuku.requestPermission(REQUEST_PERMISSION);
    }

    static int startRestrictedProcess(Service service, String token, int userId) throws IOException, InterruptedException {
        final Object lock = new Object();
        final IShellServiceBootstrap[] bootstrap = new IShellServiceBootstrap[1];
        final boolean[] disconnected = {false};
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
                synchronized (lock) { bootstrap[0] = IShellServiceBootstrap.Stub.asInterface(binder); lock.notifyAll(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                synchronized (lock) { disconnected[0] = true; lock.notifyAll(); }
            }
        };
        final var args = new Shizuku.UserServiceArgs(new ComponentName(MagicDeskApplication.applicationContext(), ShellServiceBootstrap.class))
                .daemon(false).processNameSuffix("bootstrap").tag("bootstrap-" + token)
                .version(BuildConfig.SOURCE_ID.hashCode() & Integer.MAX_VALUE);
        try {
            Shizuku.bindUserService(args, connection);
            synchronized (lock) {
                final long deadline = android.os.SystemClock.uptimeMillis() + 10_000;
                while (bootstrap[0] == null) {
                    final long remaining = deadline - android.os.SystemClock.uptimeMillis();
                    if (remaining <= 0 || disconnected[0]) throw new IOException("Shizuku process bootstrap unavailable");
                    EventDrivenWaits.await(lock, EventDrivenWaits.Reason.SERVICE_BINDING, remaining);
                }
            }
            return bootstrap[0].start(service.name(), token, userId);
        } catch (android.os.RemoteException error) {
            throw new IOException("Shizuku process bootstrap failed", error);
        } finally {
            try { Shizuku.unbindUserService(args, connection, true); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override public void openManager(Context context) {
        final Intent intent = context.getPackageManager().getLaunchIntentForPackage(IntegrationPackage.SHIZUKU.selected());
        if (intent != null) {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (IntegrationPackage.SHIZUKU.defaultPackage.equals(IntegrationPackage.SHIZUKU.selected())) {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
        } else {
            android.widget.Toast.makeText(context, context.getString(R.string.settings_integration_missing_manager,
                    IntegrationPackage.SHIZUKU.selected()), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
