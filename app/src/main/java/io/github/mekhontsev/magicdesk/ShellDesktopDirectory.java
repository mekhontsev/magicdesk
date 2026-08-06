package io.github.mekhontsev.magicdesk;

import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ShellDesktopDirectory implements AutoCloseable {
    static final String ABSOLUTE_PATH = "/storage/emulated/0/Desktop";

    private static final int OBSERVED_EVENTS = FileObserver.CREATE
            | FileObserver.DELETE
            | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO
            | FileObserver.CLOSE_WRITE
            | FileObserver.ATTRIB;
    private static final String BINARY_MIME = "application/octet-stream";

    private final Object mLock = new Object();
    private final Path mRoot = Path.of(ABSOLUTE_PATH).toAbsolutePath().normalize();
    private final RemoteCallbackList<IDesktopFolderObserverCallback> mCallbacks =
            new RemoteCallbackList<>() {
                @Override
                public void onCallbackDied(
                        final IDesktopFolderObserverCallback callback,
                        final Object cookie) {
                    synchronized (mLock) {
                        if (getRegisteredCallbackCount() == 0) {
                            stopObserverLocked();
                        }
                    }
                }
            };
    private FileObserver mObserver;

    void ensureDirectory() {
        try {
            Files.createDirectories(mRoot);
            if (Files.isSymbolicLink(mRoot)
                    || !Files.isDirectory(mRoot)) {
                throw new IOException("desktop path is not a directory");
            }
        } catch (IOException error) {
            throw failure("cannot create desktop directory", error);
        }
    }

    DesktopFileInfo[] list() {
        ensureDirectory();
        final List<DesktopFileInfo> files = new ArrayList<>();
        try (var stream = Files.list(mRoot)) {
            stream.forEach(path -> {
                try {
                    if (!Files.isSymbolicLink(path)) {
                        files.add(toInfo(path));
                    }
                } catch (IOException ignored) {
                    // A single concurrently removed entry must not hide the rest.
                }
            });
        } catch (IOException error) {
            throw failure("cannot list desktop directory", error);
        }
        return files.toArray(new DesktopFileInfo[0]);
    }

    ParcelFileDescriptor open(final String relativePath) {
        final Path path = existingEntry(relativePath);
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("cannot open a directory as a file");
        }
        try {
            return ParcelFileDescriptor.open(
                    path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException error) {
            throw failure("cannot open desktop file", error);
        }
    }

    DesktopFileInfo info(final String relativePath) {
        final Path path = existingEntry(relativePath);
        try {
            return toInfo(path);
        } catch (IOException error) {
            throw failure("cannot read desktop entry", error);
        }
    }

    DesktopFileInfo create(final String requestedName, final boolean directory) {
        ensureDirectory();
        final String name = DesktopPathPolicy.validateName(requestedName);
        final Path path = childOf(mRoot, name);
        try {
            if (directory) {
                Files.createDirectory(path);
            } else {
                Files.createFile(path);
            }
            return toInfo(path);
        } catch (IOException error) {
            throw failure("cannot create desktop entry", error);
        }
    }

    DesktopFileInfo rename(
            final String relativePath, final String requestedName) {
        final Path source = existingEntry(relativePath);
        final String name = DesktopPathPolicy.validateName(requestedName);
        final Path target = childOf(source.getParent(), name);
        if (source.equals(target)) {
            try {
                return toInfo(source);
            } catch (IOException error) {
                throw failure("cannot read desktop entry", error);
            }
        }
        try {
            Files.move(source, target);
            return toInfo(target);
        } catch (IOException error) {
            throw failure("cannot rename desktop entry", error);
        }
    }

    void delete(final String relativePath) {
        final Path target = existingEntry(relativePath);
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(
                        final Path file,
                        final BasicFileAttributes attributes)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                        final Path directory, final IOException error)
                        throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException error) {
            throw failure("cannot delete desktop entry", error);
        }
    }

    void startObserver(final IDesktopFolderObserverCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("missing desktop folder observer");
        }
        ensureDirectory();
        synchronized (mLock) {
            mCallbacks.register(callback);
            if (mObserver != null) {
                return;
            }
            mObserver = new FileObserver(mRoot.toFile(), OBSERVED_EVENTS) {
                @Override
                public void onEvent(final int event, final String path) {
                    notifyChanged();
                }
            };
            mObserver.startWatching();
        }
    }

    void stopObserver(final IDesktopFolderObserverCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (mLock) {
            mCallbacks.unregister(callback);
            if (mCallbacks.getRegisteredCallbackCount() == 0) {
                stopObserverLocked();
            }
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {
            stopObserverLocked();
            mCallbacks.kill();
        }
    }

    private DesktopFileInfo toInfo(final Path path) throws IOException {
        final BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class);
        final boolean directory = attributes.isDirectory();
        final String name = path.getFileName().toString();
        return new DesktopFileInfo(
                mRoot.relativize(path).toString(),
                name,
                directory ? DocumentsContract.Document.MIME_TYPE_DIR
                        : mimeType(name),
                attributes.lastModifiedTime().toMillis(),
                directory ? 0L : attributes.size(),
                directory);
    }

    private Path existingEntry(final String relativePath) {
        ensureDirectory();
        final Path path = resolveRelative(relativePath);
        if (path.equals(mRoot)) {
            throw new IllegalArgumentException("desktop root cannot be modified");
        }
        if (!Files.exists(path) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("desktop entry does not exist");
        }
        rejectSymbolicParents(path);
        return path;
    }

    private Path resolveRelative(final String relativePath) {
        return DesktopPathPolicy.resolve(mRoot, relativePath);
    }

    private void rejectSymbolicParents(final Path path) {
        Path current = mRoot;
        final Path relative = mRoot.relativize(path);
        for (final Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("symbolic links are not supported");
            }
        }
    }

    private Path childOf(final Path parent, final String name) {
        final Path child = parent.resolve(name).normalize();
        if (!child.startsWith(mRoot) || child.equals(mRoot)) {
            throw new IllegalArgumentException("invalid desktop entry name");
        }
        rejectSymbolicParents(parent);
        return child;
    }

    private static String mimeType(final String name) {
        final int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return BINARY_MIME;
        }
        final String extension = name.substring(dot + 1)
                .toLowerCase(Locale.ROOT);
        final String mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension);
        return mime == null ? BINARY_MIME : mime;
    }

    private void notifyChanged() {
        final int count = mCallbacks.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                try {
                    mCallbacks.getBroadcastItem(index)
                            .onDesktopFolderChanged();
                } catch (RemoteException ignored) {
                    // RemoteCallbackList removes dead callback binders.
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
    }

    private void stopObserverLocked() {
        if (mObserver == null) {
            return;
        }
        mObserver.stopWatching();
        mObserver = null;
    }

    private static IllegalStateException failure(
            final String operation, final IOException error) {
        final String message = error.getMessage();
        return new IllegalStateException(
                operation + (message == null ? "" : ": " + message), error);
    }
}
