package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** A lifecycle-owned interactive pseudo-terminal hosted by the UserService. */
final class ShellPtyHandle implements Closeable {
    private static final int BINDER_WRITE_CHUNK_BYTES = 32 * 1024;

    private final long mRequestId;
    private final InputStream mInput;
    @SuppressWarnings("unused")
    private final IBinder mOwnerToken;
    private final IShizukuCommandService mService;
    private final AtomicBoolean mClosed = new AtomicBoolean();

    ShellPtyHandle(
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

    void write(final byte[] data) throws IOException {
        if (mClosed.get()) {
            throw new IOException("Shizuku PTY is closed");
        }
        try {
            for (int offset = 0; offset < data.length;
                    offset += BINDER_WRITE_CHUNK_BYTES) {
                final int count = Math.min(
                        BINDER_WRITE_CHUNK_BYTES, data.length - offset);
                final byte[] chunk;
                if (offset == 0 && count == data.length) {
                    chunk = data;
                } else {
                    chunk = new byte[count];
                    System.arraycopy(data, offset, chunk, 0, count);
                }
                mService.writeStreamBytes(mRequestId, chunk);
            }
        } catch (RemoteException | RuntimeException error) {
            throw new IOException(
                    "Shizuku PTY write failed: "
                            + ShellAccess.usefulMessage(error),
                    error);
        }
    }

    void resize(final int rows, final int columns) throws IOException {
        if (mClosed.get()) {
            throw new IOException("Shizuku PTY is closed");
        }
        try {
            mService.resizePtyStream(mRequestId, rows, columns);
        } catch (RemoteException | RuntimeException error) {
            throw new IOException(
                    "Shizuku PTY resize failed: "
                            + ShellAccess.usefulMessage(error),
                    error);
        }
    }

    String workingDirectory() throws IOException {
        if (mClosed.get()) {
            throw new IOException("Shizuku PTY is closed");
        }
        try {
            return mService.getPtyWorkingDirectory(mRequestId);
        } catch (RemoteException | RuntimeException error) {
            throw new IOException(
                    "Shizuku PTY directory lookup failed: "
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
            // The remote PTY may already have ended.
        }
        try {
            mService.closeStream(mRequestId);
        } catch (RemoteException | RuntimeException ignored) {
            // Closing a disconnected UserService is already complete.
        }
    }
}
