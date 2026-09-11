package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A private Binder handoff for a child started through su or a one-shot Shizuku bootstrap. */
final class ShellProcessLauncher implements ShellServiceLauncher {
    private static final long START_TIMEOUT_MILLIS = 60_000;
    private static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ShellBackend mBackend;
    private final int mUid;
    private volatile ShellAccess.Snapshot mSnapshot;
    private volatile boolean mMayStart = true;
    private Runnable mChanged;

    ShellProcessLauncher(ShellBackend backend) {
        mBackend = backend;
        mUid = ShellPrivilegePolicy.targetUid(0);
        mSnapshot = ShellAccess.Snapshot.unavailable(backend, true, "Privileged process has not connected");
    }

    @Override public void initialize(Runnable changed, Runnable disconnected) { mChanged = changed; }
    @Override public ShellAccess.Snapshot inspect() { return mSnapshot; }
    @Override public boolean canBind() { return mMayStart; }
    @Override public long bindTimeoutMillis() { return START_TIMEOUT_MILLIS; }

    @Override public Binding bind(Service service, String tag, ServiceConnection connection) {
        final Pending pending = new Pending(this, service, connection);
        if (service == Service.COMMAND) {
            mMayStart = false;
            mSnapshot = ShellAccess.Snapshot.unavailable(mBackend, true,
                    mBackend == ShellBackend.ROOT
                            ? "Connecting privileged service; approve MagicDesk in the root manager if requested"
                            : "Starting shell service through Shizuku");
        }
        PENDING.put(pending.token, pending);
        // This is the deadline for authorization and the one-shot Binder handoff, not a retry delay.
        mMain.postDelayed(pending.timeout, START_TIMEOUT_MILLIS);
        new Thread(() -> launch(pending), "MagicDeskServiceBootstrap").start();
        return pending;
    }

    private void launch(Pending pending) {
        try {
            final Context context = MagicDeskApplication.applicationContext();
            final int userId = FrameworkUserApi.userId(android.os.Process.myUserHandle());
            if (mBackend == ShellBackend.SHIZUKU) {
                pending.confirmPid(ShizukuServiceLauncher.startRestrictedProcess(pending.service, pending.token, userId));
                return;
            }
            final String command = java.util.Arrays.stream(ShellServiceStartup.command(context,
                    pending.service, pending.token, userId, mUid)).map(ShellCommandLine::quote)
                    .collect(java.util.stream.Collectors.joining(" "));
            final var result = BoundedProcessRunner.run(new ProcessBuilder("su", "-c", "exec " + command)
                    .redirectErrorStream(true).start(), START_TIMEOUT_MILLIS, 8192);
            if (result.exitCode != 0) throw new IOException("su exited " + result.exitCode + ": " + result.output.trim());
            pending.confirmPid(parsePid(result.output));
        } catch (IOException | RuntimeException error) {
            mMain.post(() -> pending.fail(ShellAccess.usefulMessage(error)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            mMain.post(() -> pending.fail("root startup interrupted"));
        }
    }

    static int parsePid(String output) throws IOException {
        int pid = -1;
        for (String line : output.split("\\R")) {
            if (!line.startsWith("MAGICDESK_PID=")) continue;
            try {
                if (pid != -1) throw new IOException("Bootstrap reported multiple PIDs");
                pid = Integer.parseInt(line.substring("MAGICDESK_PID=".length()));
                if (pid <= 0) throw new IOException("Bootstrap reported an invalid PID");
            } catch (NumberFormatException error) { throw new IOException("Bootstrap reported an invalid PID", error); }
        }
        if (pid == -1) throw new IOException("Bootstrap did not report the service PID");
        return pid;
    }

    static Bundle attach(String token, Bundle extras, int pid, int uid) {
        final Pending pending = token == null ? null : PENDING.get(token);
        if (pending == null || !BuildConfig.SOURCE_ID.equals(extras.getString("sourceId"))) {
            throw new SecurityException("unknown root startup or APK build");
        }
        if (pending.launcher.mUid != uid) throw new SecurityException("unexpected service UID");
        return pending.attach(extras.getBinder("service"), pid);
    }

    @Override public void requestPermission() {
        mMayStart = true;
        mChanged.run();
    }
    @Override public void openManager(Context context) { requestPermission(); }

    private static final class Pending implements Binding {
        final String token = UUID.randomUUID().toString();
        final long deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS;
        final ShellProcessLauncher launcher;
        final Service service;
        final ServiceConnection connection;
        final Binder owner = new Binder();
        final Runnable timeout = () -> fail("Privilege authorization or Binder handoff timed out");
        final IBinder.DeathRecipient death;
        private IBinder mBinder;
        private boolean mClosed;
        private int mExpectedPid;

        Pending(ShellProcessLauncher launcher, Service service, ServiceConnection connection) {
            this.launcher = launcher;
            this.service = service;
            this.connection = connection;
            death = () -> launcher.mMain.post(() -> fail("root service disconnected"));
        }

        synchronized Bundle attach(IBinder binder, int pid) {
            // A shell-UID sibling must not win a nonce race: su independently reports the real child PID.
            while (!mClosed && mExpectedPid == 0) {
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) break;
                try { EventDrivenWaits.await(this, EventDrivenWaits.Reason.SERVICE_BINDING, remaining); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("root PID confirmation interrupted", error);
                }
            }
            if (mClosed || mBinder != null || SystemClock.uptimeMillis() >= deadline || binder == null || pid <= 0) {
                throw new SecurityException("expired or invalid root handoff");
            }
            if (pid != mExpectedPid) throw new SecurityException("foreign service PID");
            try { binder.linkToDeath(death, 0); }
            catch (android.os.RemoteException error) { throw new IllegalStateException("root process died during handoff", error); }
            mBinder = binder;
            PENDING.remove(token, this);
            launcher.mMain.removeCallbacks(timeout);
            launcher.mMain.post(() -> {
                synchronized (this) {
                    if (mClosed) return;
                    if (service == Service.COMMAND) {
                        launcher.mMayStart = true;
                        launcher.mSnapshot = new ShellAccess.Snapshot(launcher.mBackend, true, true, true,
                                launcher.mUid, -1, "");
                    }
                }
                connection.onServiceConnected(component(), binder);
            });
            final Bundle response = new Bundle();
            response.putBinder("owner", owner);
            return response;
        }

        private ComponentName component() { return new ComponentName(BuildConfig.APPLICATION_ID, service.implementation.getName()); }

        synchronized void confirmPid(int pid) {
            mExpectedPid = pid;
            notifyAll();
        }

        void fail(String error) {
            synchronized (this) {
                if (mClosed) return;
                mClosed = true;
                PENDING.remove(token, this);
                launcher.mMain.removeCallbacks(timeout);
                notifyAll();
                if (service == Service.COMMAND) launcher.mSnapshot = ShellAccess.Snapshot.unavailable(launcher.mBackend, true, error);
            }
            Log.w("MagicDeskRoot", error);
            connection.onServiceDisconnected(component());
        }

        @Override public void close(boolean terminate) {
            final IBinder binder;
            synchronized (this) {
                mClosed = true;
                PENDING.remove(token, this);
                launcher.mMain.removeCallbacks(timeout);
                binder = mBinder;
                mBinder = null;
                notifyAll();
            }
            if (binder == null) return;
            binder.unlinkToDeath(death, 0);
            if (terminate) {
                try {
                    if (service == Service.COMMAND) IShellCommandService.Stub.asInterface(binder).destroy();
                    else IAppUpdateWorker.Stub.asInterface(binder).destroy();
                } catch (android.os.RemoteException ignored) { /* Process exit can precede the reply. */ }
            }
        }
    }
}
