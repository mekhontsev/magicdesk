package io.github.mekhontsev.magicdesk.platform.nubia;

import java.io.IOException;

/** Journals only package state changed by the projection guard. */
final class NubiaProjectionPackageLease {
    static final int DEFAULT = 0;
    static final int ENABLED = 1;
    static final int DISABLED_USER = 3;

    interface Backend {
        int readState() throws IOException;
        void writeState(int state) throws IOException;
        Integer readRestoreState();
        void saveRestoreState(Integer state) throws IOException;
    }

    private final Backend mBackend;

    NubiaProjectionPackageLease(final Backend backend) {
        mBackend = backend;
    }

    void acquire() throws IOException {
        final int current = mBackend.readState();
        if (current != DEFAULT && current != ENABLED) {
            return;
        }
        mBackend.saveRestoreState(current);
        mBackend.writeState(DISABLED_USER);
        if (mBackend.readState() != DISABLED_USER) {
            throw new IOException("projection package was not disabled");
        }
    }

    void release() throws IOException {
        final Integer restore = mBackend.readRestoreState();
        if (restore == null) { return; }
        if (mBackend.readState() == DISABLED_USER) {
            mBackend.writeState(restore);
            if (mBackend.readState() != restore) {
                throw new IOException("projection package was not restored");
            }
        }
        // A later user/package-manager change belongs to its caller, not us.
        mBackend.saveRestoreState(null);
    }
}
