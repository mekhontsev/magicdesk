package io.github.mekhontsev.magicdesk;

import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ShellDesktopDirectory implements AutoCloseable {
    static final String ABSOLUTE_PATH = "/storage/emulated/0/Desktop";
    static final String METADATA_DIRECTORY = ".magicdesk";
    static final String STATE_RELATIVE_PATH = METADATA_DIRECTORY + "/desktop.json";
    static final String WALLPAPER_RELATIVE_PATH = METADATA_DIRECTORY + "/wallpaper";

    private static final int OBSERVED_EVENTS = FileObserver.CREATE
            | FileObserver.DELETE
            | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO
            | FileObserver.CLOSE_WRITE
            | FileObserver.ATTRIB;
    private static final String BINARY_MIME = "application/octet-stream";
    private static final int MAX_STATE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_WALLPAPER_BYTES = 64L * 1024L * 1024L;
    private static final int COPY_BUFFER_SIZE = 32 * 1024;

    private final Object mLock = new Object();
    private final Path mRoot = Path.of(ABSOLUTE_PATH).toAbsolutePath().normalize();
    private final Path mMetadata = mRoot.resolve(METADATA_DIRECTORY);
    private final Path mState = mMetadata.resolve("desktop.json");
    private final Path mWallpaper = mMetadata.resolve("wallpaper");
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
    private FileObserver mRootObserver;
    private FileObserver mMetadataObserver;

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
                    if (!METADATA_DIRECTORY.equals(
                                    path.getFileName().toString())
                            && !Files.isSymbolicLink(path)) {
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

    ParcelFileDescriptor open(final String relativePath, final String mode) {
        final Path path = existingEntry(relativePath);
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("cannot open a directory as a file");
        }
        try {
            return ParcelFileDescriptor.open(
                    path.toFile(), ParcelFileDescriptor.parseMode(mode));
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
        ensureMetadataDirectory();
        synchronized (mLock) {
            mCallbacks.register(callback);
            if (mRootObserver != null) {
                return;
            }
            mRootObserver = new FileObserver(mRoot.toFile(), OBSERVED_EVENTS) {
                @Override
                public void onEvent(final int event, final String path) {
                    if (METADATA_DIRECTORY.equals(path)
                            && (event & (FileObserver.CREATE
                                    | FileObserver.DELETE
                                    | FileObserver.MOVED_FROM
                                    | FileObserver.MOVED_TO)) != 0) {
                        synchronized (mLock) {
                            if (mRootObserver != null) {
                                restartMetadataObserverLocked();
                            }
                        }
                    }
                    notifyChanged(path == null ? "" : path);
                }
            };
            mRootObserver.startWatching();
            restartMetadataObserverLocked();
        }
    }

    String readState() {
        ensureMetadataDirectory();
        if (!Files.exists(mState)) {
            return null;
        }
        validateMetadataFile(mState, MAX_STATE_BYTES);
        try {
            return new String(
                    Files.readAllBytes(mState), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw failure("cannot read desktop state", error);
        }
    }

    void writeState(final String encodedState) {
        if (encodedState == null) {
            throw new IllegalArgumentException("missing desktop state");
        }
        final byte[] bytes = encodedState.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("desktop state is too large");
        }
        ensureMetadataDirectory();
        writeAtomically(mState, output -> output.write(bytes));
    }

    ParcelFileDescriptor openWallpaper() {
        ensureMetadataDirectory();
        if (!Files.exists(mWallpaper)) {
            return null;
        }
        validateMetadataFile(mWallpaper, MAX_WALLPAPER_BYTES);
        try {
            return ParcelFileDescriptor.open(
                    mWallpaper.toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException error) {
            throw failure("cannot open desktop wallpaper", error);
        }
    }

    void writeWallpaper(final ParcelFileDescriptor source) {
        if (source == null) {
            throw new IllegalArgumentException("missing desktop wallpaper");
        }
        ensureMetadataDirectory();
        writeAtomically(mWallpaper, output -> {
            try (InputStream input =
                    new ParcelFileDescriptor.AutoCloseInputStream(source)) {
                final byte[] buffer = new byte[COPY_BUFFER_SIZE];
                long total = 0L;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    total += count;
                    if (total > MAX_WALLPAPER_BYTES) {
                        throw new IOException("desktop wallpaper is too large");
                    }
                    output.write(buffer, 0, count);
                }
                if (total == 0L) {
                    throw new IOException("desktop wallpaper is empty");
                }
            }
        });
    }

    boolean deleteWallpaper() {
        ensureMetadataDirectory();
        try {
            return Files.deleteIfExists(mWallpaper);
        } catch (IOException error) {
            throw failure("cannot delete desktop wallpaper", error);
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
        final Path resolved = DesktopPathPolicy.resolve(mRoot, relativePath);
        final Path relative = mRoot.relativize(resolved);
        if (relative.getNameCount() > 0
                && METADATA_DIRECTORY.equals(
                        relative.getName(0).toString())) {
            throw new IllegalArgumentException(
                    "MagicDesk metadata is not a desktop entry");
        }
        return resolved;
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
        if (child.startsWith(mMetadata)) {
            throw new IllegalArgumentException(
                    "MagicDesk metadata is not a desktop entry");
        }
        return child;
    }

    private void ensureMetadataDirectory() {
        ensureDirectory();
        try {
            Files.createDirectories(mMetadata);
            if (Files.isSymbolicLink(mMetadata)
                    || !Files.isDirectory(mMetadata)) {
                throw new IOException("metadata path is not a directory");
            }
        } catch (IOException error) {
            throw failure("cannot create MagicDesk metadata directory", error);
        }
    }

    private static void validateMetadataFile(
            final Path path, final long maximumSize) {
        try {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path)
                    || Files.size(path) > maximumSize) {
                throw new IOException("invalid MagicDesk metadata file");
            }
        } catch (IOException error) {
            throw failure("cannot validate MagicDesk metadata", error);
        }
    }

    private void writeAtomically(
            final Path destination,
            final FileWriter writer) {
        final Path pending = destination.resolveSibling(
                destination.getFileName() + ".pending");
        try {
            Files.deleteIfExists(pending);
            try (OutputStream output = Files.newOutputStream(
                    pending,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                writer.write(output);
                output.flush();
            }
            try {
                Files.move(
                        pending,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        pending,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            try {
                Files.deleteIfExists(pending);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
            throw failure("cannot update MagicDesk metadata", error);
        }
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

    private void notifyChanged(final String relativePath) {
        final int count = mCallbacks.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                try {
                    mCallbacks.getBroadcastItem(index)
                            .onDesktopFolderChanged(relativePath);
                } catch (RemoteException ignored) {
                    // RemoteCallbackList removes dead callback binders.
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
    }

    private void stopObserverLocked() {
        if (mRootObserver != null) {
            mRootObserver.stopWatching();
            mRootObserver = null;
        }
        if (mMetadataObserver != null) {
            mMetadataObserver.stopWatching();
            mMetadataObserver = null;
        }
    }

    private void restartMetadataObserverLocked() {
        if (mMetadataObserver != null) {
            mMetadataObserver.stopWatching();
        }
        ensureMetadataDirectory();
        mMetadataObserver = new FileObserver(
                mMetadata.toFile(), OBSERVED_EVENTS) {
            @Override
            public void onEvent(final int event, final String path) {
                notifyChanged(path == null
                        ? METADATA_DIRECTORY
                        : METADATA_DIRECTORY + "/" + path);
            }
        };
        mMetadataObserver.startWatching();
    }

    @FunctionalInterface
    private interface FileWriter {
        void write(OutputStream output) throws IOException;
    }

    private static IllegalStateException failure(
            final String operation, final IOException error) {
        final String message = error.getMessage();
        return new IllegalStateException(
                operation + (message == null ? "" : ": " + message), error);
    }
}
