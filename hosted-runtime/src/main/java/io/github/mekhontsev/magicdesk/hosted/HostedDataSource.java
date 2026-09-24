package io.github.mekhontsev.magicdesk.hosted;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;

/** Lazy content in one execution environment; no Android clipboard or protocol policy. */
public interface HostedDataSource {
    long MAX_BYTES = 128L * 1024 * 1024;
    List<String> types();
    /** Independently owned, seekable descriptor. Null rejects the requested format. Worker-thread only. */
    ParcelFileDescriptor open(String type) throws IOException;

    static ParcelFileDescriptor bytes(byte[] bytes) throws IOException {
        if (bytes.length > 1024 * 1024) throw new IOException("Inline selection exceeds 1 MiB");
        FileDescriptor fd = null;
        try {
            // Linux UAPI memfd/fcntl constants are not all exported by OsConstants.
            fd = Os.memfd_create("hosted-content", OsConstants.MFD_CLOEXEC | 2 /* MFD_ALLOW_SEALING */);
            for (int offset = 0; offset < bytes.length;) {
                int count = Os.pwrite(fd, bytes, offset, bytes.length - offset, offset);
                if (count <= 0) throw new IOException("Incomplete content descriptor");
                offset += count;
            }
            Os.fcntlInt(fd, 1033 /* F_ADD_SEALS */, 15 /* SEAL_SEAL | SHRINK | GROW | WRITE */);
            return ParcelFileDescriptor.dup(fd);
        } catch (ErrnoException error) { throw new IOException("Cannot create content descriptor", error); }
        finally { if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { } }
    }
}
