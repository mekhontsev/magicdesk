package io.github.mekhontsev.magicdesk.hosted;

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

/** Session file exchange: server-local paths or the explicitly selected guest's read-only bridge. */
public final class HostedFileExchange {
    private static final long MAX_BYTES = 128L * 1024 * 1024, MAX_STORAGE = 256L * 1024 * 1024;
    private int fileCount;
    private volatile boolean closed;
    private File directory;
    private long used;
    private final SharedFileNamespace shared;
    private final String contentDirectory;
    private final GuestFileBridge guest;

    public HostedFileExchange(String directory, String guestAlias, String endpoint, String token) throws IOException {
        contentDirectory = java.util.Objects.requireNonNull(directory);
        shared = guestAlias == null ? null : new SharedFileNamespace(directory, guestAlias);
        guest = endpoint == null ? null : new GuestFileBridge(endpoint, token);
    }

    public ParcelFileDescriptor open(String value) throws IOException {
        if (closed) throw new IOException("File exchange closed");
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException error) { throw new IOException("Invalid file URI", error); }
        if (!"file".equals(uri.getScheme()) || uri.getQuery() != null || uri.getFragment() != null ||
                (uri.getAuthority() != null && !uri.getAuthority().isEmpty() && !"localhost".equals(uri.getAuthority())))
            throw new IOException("Only local files can be exported");
        String path = uri.getPath();
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0) throw new IOException("Invalid file path");
        if (guest != null) {
            ParcelFileDescriptor file = guest.open(path);
            try {
                var stat = Os.fstat(file.getFileDescriptor());
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0 || stat.st_size > MAX_BYTES)
                    throw new IOException("Only regular files up to 128 MiB can be exported");
                return file;
            } catch (ErrnoException | IOException | RuntimeException error) {
                file.close();
                throw new IOException("Invalid guest file", error);
            }
        }
        if (shared != null) path = shared.hostPath(path);
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK, 0);
            if (shared != null) try (var copy = ParcelFileDescriptor.dup(descriptor)) {
                shared.requireInside(Os.readlink("/proc/self/fd/" + copy.getFd()));
            }
            var stat = Os.fstat(descriptor);
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0 || stat.st_size > MAX_BYTES)
                throw new IOException("Only regular files up to 128 MiB can be exported");
            return ParcelFileDescriptor.dup(descriptor);
        } catch (ErrnoException error) { throw new IOException("Cannot read file in the selected environment", error); }
        finally { if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { } }
    }

    public synchronized String importFile(ParcelFileDescriptor source, String name) throws IOException {
        if (source == null) throw new IOException("Missing file descriptor");
        try (source) {
            long size = source.getStatSize();
            if (closed) throw new IOException("File exchange closed");
            if (size < 0 || size > MAX_BYTES || size > MAX_STORAGE - used || fileCount >= 128)
                throw new IOException("File exchange storage is full or source is not seekable");
            if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 240 ||
                    name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
                throw new IOException("Invalid imported file name");
            if (directory == null) {
                directory = new File(contentDirectory);
                Files.createDirectories(directory.toPath());
                if (shared != null) chmod(directory, 01777);
            }
            File container = new File(directory, UUID.randomUUID().toString());
            Files.createDirectory(container.toPath());
            if (shared != null) chmod(container, 0755);
            File target = new File(container, name);
            File pending = Files.createTempFile(directory.toPath(), "transfer-", ".partial").toFile();
            boolean complete = false;
            try {
                try (var input = new ParcelFileDescriptor.AutoCloseInputStream(source);
                     var output = Files.newOutputStream(pending.toPath())) {
                    byte[] bytes = new byte[65536];
                    long copied = 0;
                    for (int count; (count = input.read(bytes)) >= 0;) {
                        if (closed) throw new IOException("File exchange closed");
                        copied += count;
                        if (copied > size) throw new IOException("Imported file changed while reading");
                        output.write(bytes, 0, count);
                    }
                    if (copied != size) throw new IOException("Incomplete file transfer");
                }
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                if (shared != null) chmod(target, 0644);
                fileCount++;
                used += size;
                complete = true;
                return shared == null ? target.toURI().toASCIIString() : shared.guestUri(target.toString());
            } finally {
                if (!complete) { Files.deleteIfExists(pending.toPath()); Files.deleteIfExists(container.toPath()); }
            }
        }
    }

    // The launcher owns this private directory and removes it after process exit.
    // Cancellation must not block server shutdown behind an in-flight Binder copy.
    public void close() { closed = true; if (guest != null) guest.close(); }

    private static void chmod(File file, int mode) throws IOException {
        try { Os.chmod(file.toString(), mode); }
        catch (ErrnoException error) { throw new IOException("Cannot prepare the shared content directory", error); }
    }
}
