package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.Closeable;
import java.io.IOException;
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

    int keyboardAssociationCount() {
        return mInitialState[2];
    }

    int virtualKeyboardCount() {
        return mInitialState[3];
    }

    int refresh() throws IOException {
        if (mClosed.get()) {
            throw new IOException("input routing is closed");
        }
        try {
            return mService.refreshInputRouting();
        } catch (RemoteException | RuntimeException error) {
            throw new IOException(
                    "input routing refresh failed: "
                            + ShellAccess.usefulMessage(error),
                    error);
        }
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
