package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShellInputRoutingHandle implements Closeable {
    private final IShizukuCommandService mService;
    private final IBinder mOwnerToken;
    private final int[] mInitialState;
    private final AtomicBoolean mClosed = new AtomicBoolean();

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

    int virtualKeyboardCount() {
        return mInitialState[2];
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            mService.stopInputRouting(mOwnerToken);
        } catch (RemoteException | RuntimeException ignored) {
            // A disconnected UserService has already released the routing session.
        }
    }
}
