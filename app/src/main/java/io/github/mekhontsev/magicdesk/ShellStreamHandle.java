package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShellStreamHandle implements Closeable {
    private final long mRequestId;
    private final InputStream mInput;
    // The remote UserService owns the stream only while this Binder is alive.
    @SuppressWarnings("unused")
    private final IBinder mOwnerToken;
    private final IShizukuCommandService mService;
    private final AtomicBoolean mClosed = new AtomicBoolean();

    ShellStreamHandle(
            final long requestId,
            final ParcelFileDescriptor descriptor,
            final IBinder ownerToken,
            final IShizukuCommandService service) {
        mRequestId = requestId;
        mInput = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        mOwnerToken = ownerToken;
        mService = service;
    }

    InputStream inputStream() {
        return mInput;
    }

    void writeLine(final String line) throws IOException {
        if (mClosed.get()) {
            throw new IOException("Shizuku stream is closed");
        }
        try {
            mService.writeStream(mRequestId, line);
        } catch (RemoteException | RuntimeException error) {
            throw new IOException(
                    "Shizuku stream write failed: "
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
            mInput.close();
        } catch (IOException ignored) {
            // The remote stream may already have ended.
        }
        try {
            mService.closeStream(mRequestId);
        } catch (RemoteException | RuntimeException ignored) {
            // Closing a disconnected UserService is already complete.
        }
    }
}
