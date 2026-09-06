package io.github.mekhontsev.magicdesk;

import android.os.RemoteException;

import java.io.IOException;

/** Search cancellation stays with the UserService that allocated the id. */
final class ShellFileSearchHandle {
    final long id;
    private final IShizukuCommandService mService;

    ShellFileSearchHandle(final long id, final IShizukuCommandService service)
            throws IOException {
        if (id <= 0L) {
            throw new IOException("invalid shell file search id");
        }
        this.id = id;
        mService = service;
    }

    void cancel() throws IOException {
        try {
            mService.cancelShellFileSearch(id);
        } catch (RemoteException | RuntimeException error) {
            throw new IOException("file search cancellation failed", error);
        }
    }
}
