package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.Closeable;
import java.io.IOException;

final class ShellInputRoutingHandle implements Closeable {
    private final IShizukuCommandService mService;
    private final IBinder mOwnerToken;
    private final int[] mInitialState;
    private boolean mClosed;

    ShellInputRoutingHandle(
            final IShizukuCommandService service,
            final IBinder ownerToken,
            final int[] initialState) {
        mService = service;
        mOwnerToken = ownerToken;
        mInitialState = initialState.clone();
    }

    int displayId() {
        return mInitialState[0];
    }

    int associationCount() {
        return mInitialState[1];
    }

    synchronized void refresh() throws IOException {
        if (mClosed) {
            return;
        }
        try {
            mService.refreshInputRouting(mOwnerToken);
        } catch (RemoteException | RuntimeException error) {
            throw new IOException("input routing refresh failed", error);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (mClosed) {
            return;
        }
        try {
            mService.stopInputRouting(mOwnerToken);
            mClosed = true;
        } catch (RemoteException | RuntimeException error) {
            throw new IOException("input routing restoration failed", error);
        }
    }
}
