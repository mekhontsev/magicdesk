package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.IOException;
import java.util.function.Supplier;

import rikka.shizuku.Shizuku;

final class ShellServiceConnection {
    private static final long BIND_TIMEOUT_MILLIS = 10_000;

    private final Object mLock = new Object();
    private final Runnable mConnectedCallback;
    private IShizukuCommandService mService;
    private boolean mBinding;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(
                final ComponentName componentName,
                final IBinder binder) {
            synchronized (mLock) {
                mService = binder != null && binder.pingBinder()
                        ? IShizukuCommandService.Stub.asInterface(binder) : null;
                mBinding = false;
                mLock.notifyAll();
            }
            mConnectedCallback.run();
        }

        @Override
        public void onServiceDisconnected(final ComponentName componentName) {
            clear();
        }
    };

    ShellServiceConnection(final Runnable connectedCallback) {
        mConnectedCallback = connectedCallback;
    }

    IShizukuCommandService require(
            final ShellAccess.Snapshot snapshot,
            final Supplier<Shizuku.UserServiceArgs> argsSupplier)
            throws IOException {
        if (!snapshot.isReady()) {
            throw new IOException(snapshot.error.isEmpty()
                    ? "Shizuku shell access is unavailable" : snapshot.error);
        }
        synchronized (mLock) {
            if (mService != null) {
                return mService;
            }
            final long deadline = SystemClock.uptimeMillis() + BIND_TIMEOUT_MILLIS;
            while (mService == null) {
                if (!mBinding) {
                    mBinding = true;
                    try {
                        Shizuku.bindUserService(argsSupplier.get(), mConnection);
                    } catch (RuntimeException error) {
                        mBinding = false;
                        throw new IOException(
                                "could not bind Shizuku command service: "
                                        + ShellAccess.usefulMessage(error),
                                error);
                    }
                }
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    mBinding = false;
                    throw new IOException("timed out binding Shizuku command service");
                }
                try {
                    mLock.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "interrupted while binding Shizuku command service",
                            error);
                }
            }
            return mService;
        }
    }

    void disconnect(final Supplier<Shizuku.UserServiceArgs> argsSupplier) {
        synchronized (mLock) {
            if (!mBinding && mService == null) {
                return;
            }
        }
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.unbindUserService(argsSupplier.get(), mConnection, true);
            }
        } catch (RuntimeException ignored) {
            // The Shizuku server may already be gone.
        } finally {
            clear();
        }
    }

    void clear() {
        synchronized (mLock) {
            mService = null;
            mBinding = false;
            mLock.notifyAll();
        }
    }
}
