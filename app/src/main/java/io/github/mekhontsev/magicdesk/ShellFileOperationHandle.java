package io.github.mekhontsev.magicdesk;

import android.os.RemoteException;

import java.io.IOException;

/** An operation id is meaningful only in the service that allocated it. */
final class ShellFileOperationHandle {
    final long id;
    private final IShellCommandService mService;

    ShellFileOperationHandle(final long id, final IShellCommandService service)
            throws IOException {
        if (id <= 0L) {
            throw new IOException("invalid shell file operation id");
        }
        this.id = id;
        mService = service;
    }

    void cancel() throws IOException {
        try {
            mService.cancelShellFileOperation(id);
        } catch (RemoteException | RuntimeException error) {
            throw new IOException("file operation cancellation failed", error);
        }
    }
}
