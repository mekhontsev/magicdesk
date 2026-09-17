package io.github.mekhontsev.magicdesk.x11;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** File access in the X server's execution identity, never the host's shell/root identity. */
final class X11FileExchange {
    private static final long MAX_BYTES = 128L * 1024 * 1024, MAX_STORAGE = 256L * 1024 * 1024;
    private int fileCount;
    private volatile boolean closed;
    private File directory;
    private long used;

    ParcelFileDescriptor open(String value) throws IOException {
        if (closed) throw new IOException("X11 file exchange closed");
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid file URI", error); }
        if (!"file".equals(uri.getScheme()) || uri.getQuery() != null || uri.getFragment() != null ||
                (uri.getAuthority() != null && !uri.getAuthority().isEmpty() && !"localhost".equals(uri.getAuthority())))
            throw new IOException("Only local files can be exported");
        String path = uri.getPath();
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0) throw new IOException("Invalid file path");
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK, 0);
            var stat = Os.fstat(descriptor);
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0 || stat.st_size > MAX_BYTES)
                throw new IOException("Only regular files up to 128 MiB can be exported");
            return ParcelFileDescriptor.dup(descriptor);
        } catch (ErrnoException error) { throw new IOException("Cannot read file in X11 environment", error); }
        finally { if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { } }
    }

    synchronized String importFile(ParcelFileDescriptor source, String name) throws IOException {
        if (source == null) throw new IOException("Missing file descriptor");
        try (source) {
            long size = source.getStatSize();
            if (closed) throw new IOException("X11 file exchange closed");
            if (size < 0 || size > MAX_BYTES || size > MAX_STORAGE - used || fileCount >= 128)
                throw new IOException("X11 file exchange storage is full or source is not seekable");
            if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 240 ||
                    name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
                throw new IOException("Invalid imported file name");
            if (directory == null) {
                String path = System.getenv("MAGICDESK_X11_CONTENT_DIR");
                if (path == null) throw new IOException("Missing X11 content directory");
                directory = new File(path);
                Files.createDirectory(directory.toPath());
            }
            File container = new File(directory, UUID.randomUUID().toString());
            Files.createDirectory(container.toPath());
            File target = new File(container, name);
            File pending = Files.createTempFile(directory.toPath(), "transfer-", ".partial").toFile();
            boolean complete = false;
            try {
                try (var input = new ParcelFileDescriptor.AutoCloseInputStream(source);
                     var output = Files.newOutputStream(pending.toPath())) {
                    byte[] bytes = new byte[65536];
                    long copied = 0;
                    for (int count; (count = input.read(bytes)) >= 0;) {
                        if (closed) throw new IOException("X11 file exchange closed");
                        copied += count;
                        if (copied > size) throw new IOException("Imported file changed while reading");
                        output.write(bytes, 0, count);
                    }
                    if (copied != size) throw new IOException("Incomplete file transfer");
                }
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                fileCount++;
                used += size;
                complete = true;
                return target.toURI().toASCIIString();
            } finally {
                if (!complete) { Files.deleteIfExists(pending.toPath()); Files.deleteIfExists(container.toPath()); }
            }
        }
    }

    // The launcher owns this private directory and removes it after process exit.
    // Cancellation must not block server shutdown behind an in-flight Binder copy.
    void close() { closed = true; }
}
