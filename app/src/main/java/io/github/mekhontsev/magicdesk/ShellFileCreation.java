package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.IOException;

/** One worker owns creation, verified writes, and rollback on the same service. */
final class ShellFileCreation implements AutoCloseable {
    final ShellFileInfo file;
    private final IShellCommandService mService;
    private boolean mFinished;

    ShellFileCreation(
            final IShellCommandService service, final String parent,
            final String name) throws IOException {
        mService = service;
        try {
            file = service.createAvailableShellEntry(parent, name, false);
            if (file == null || file.directory || file.symbolicLink) {
                throw new IOException("service returned no created ordinary file");
            }
        } catch (RemoteException | RuntimeException failure) {
            throw new IOException("cannot create file", failure);
        }
    }

    ParcelFileDescriptor open() throws IOException {
        if (mFinished) {
            throw new IOException("file creation closed");
        }
        try {
            final ParcelFileDescriptor descriptor = mService.openVerifiedShellFile(
                    file.absolutePath, "w", file.deviceId, file.inode);
            if (descriptor == null) {
                throw new IOException("service returned no created file descriptor");
            }
            return descriptor;
        } catch (RemoteException | RuntimeException failure) {
            throw new IOException("cannot open created file", failure);
        }
    }

    void commit() {
        if (mFinished) {
            throw new IllegalStateException("file creation closed");
        }
        mFinished = true;
    }

    @Override
    public void close() throws IOException {
        if (mFinished) {
            return;
        }
        mFinished = true;
        try {
            mService.deleteVerifiedShellFile(file.absolutePath, file.deviceId, file.inode);
        } catch (RemoteException | RuntimeException failure) {
            throw new IOException("cannot remove incomplete file", failure);
        }
    }
}
