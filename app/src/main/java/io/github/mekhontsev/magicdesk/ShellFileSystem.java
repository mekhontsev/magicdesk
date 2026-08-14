package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ShellFileSystem implements AutoCloseable {
    static final int OPERATION_DELETE = 1;
    static final int OPERATION_COPY = 2;
    static final int OPERATION_MOVE = 3;

    static final int SORT_NAME = 0;
    static final int SORT_MODIFIED = 1;
    static final int SORT_SIZE = 2;

    private static final LinkOption[] NO_FOLLOW = {
            LinkOption.NOFOLLOW_LINKS
    };
    private static final int MAX_PAGE_SIZE = 500;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_BYTE_INTERVAL = 1024L * 1024L;

    private final AtomicLong mNextOperationId = new AtomicLong(1L);
    private final Map<Long, FileOperation> mOperations =
            new ConcurrentHashMap<>();
    private final ExecutorService mOperationExecutor =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(
                            runnable, "MagicDeskFileOperations");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    ShellFilePage list(
            final String absolutePath,
            final int offset,
            final int requestedLimit,
            final boolean showHidden,
            final int sortMode,
            final boolean ascending) {
        final Path directory = ShellFilePathPolicy.absolute(absolutePath);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("path is not a directory");
        }
        final int safeOffset = Math.max(0, offset);
        final int limit = Math.max(1,
                Math.min(MAX_PAGE_SIZE, requestedLimit));
        final List<ShellFileInfo> entries = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.forEach(path -> {
                try {
                    final ShellFileInfo info = toInfo(path);
                    if (showHidden || !info.hidden) {
                        entries.add(info);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Concurrently removed or unreadable entries do not hide
                    // the rest of the directory.
                }
            });
        } catch (IOException error) {
            throw failure("cannot list " + directory, error);
        }
        Collections.sort(entries, comparator(sortMode, ascending));
        final int from = Math.min(safeOffset, entries.size());
        final int to = Math.min(entries.size(), from + limit);
        final List<ShellFileInfo> page = entries.subList(from, to);
        final Path parent = directory.getParent();
        return new ShellFilePage(
                directory.toString(),
                parent == null ? "" : parent.toString(),
                page.toArray(new ShellFileInfo[0]),
                to,
                to >= entries.size());
    }

    ShellFileInfo info(final String absolutePath) {
        final Path path = ShellFilePathPolicy.existing(absolutePath);
        try {
            return toInfo(path);
        } catch (IOException error) {
            throw failure("cannot read " + path, error);
        }
    }

    ParcelFileDescriptor open(
            final String absolutePath, final String mode) {
        final Path path = ShellFilePathPolicy.absolute(absolutePath);
        try {
            return ParcelFileDescriptor.open(
                    path.toFile(), ParcelFileDescriptor.parseMode(mode));
        } catch (IOException | RuntimeException error) {
            throw failure("cannot open " + path, error);
        }
    }

    ParcelFileDescriptor openVerified(
            final String absolutePath,
            final String mode,
            final long deviceId,
            final long inode) {
        final Path path = ShellFilePathPolicy.existing(absolutePath);
        ParcelFileDescriptor descriptor = null;
        try {
            final int requestedMode = ParcelFileDescriptor.parseMode(mode);
            final int nonDestructiveMode = requestedMode
                    & ~ParcelFileDescriptor.MODE_CREATE
                    & ~ParcelFileDescriptor.MODE_TRUNCATE;
            descriptor = ParcelFileDescriptor.open(
                    path.toFile(), nonDestructiveMode);
            final StructStat stat = Os.fstat(descriptor.getFileDescriptor());
            if (stat.st_dev != deviceId || stat.st_ino != inode) {
                descriptor.close();
                descriptor = null;
                throw new IllegalArgumentException(
                        "file changed after access was granted");
            }
            if ((requestedMode & ParcelFileDescriptor.MODE_TRUNCATE) != 0) {
                Os.ftruncate(descriptor.getFileDescriptor(), 0L);
            }
            return descriptor;
        } catch (ErrnoException | IOException | RuntimeException error) {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            throw failure("cannot verify " + path, error);
        }
    }

    ShellFileInfo create(
            final String parentPath,
            final String requestedName,
            final boolean directory) {
        final Path parent = ShellFilePathPolicy.absolute(parentPath);
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("parent is not a directory");
        }
        final Path target = parent.resolve(
                ShellFileNamePolicy.validate(requestedName));
        try {
            if (directory) {
                Files.createDirectory(target);
            } else {
                Files.createFile(target);
            }
            return toInfo(target);
        } catch (IOException error) {
            throw failure("cannot create " + target, error);
        }
    }

    ShellFileInfo createAvailable(
            final String parentPath,
            final String requestedName,
            final boolean directory) {
        final Path parent = ShellFilePathPolicy.absolute(parentPath);
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("parent is not a directory");
        }
        final Path requested = parent.resolve(
                ShellFileNamePolicy.validate(requestedName));
        while (true) {
            final Path target = availableTarget(requested);
            try {
                if (directory) {
                    Files.createDirectory(target);
                } else {
                    Files.createFile(target);
                }
                return toInfo(target);
            } catch (FileAlreadyExistsException ignored) {
                // Another explicit operation won the name race. Recompute.
            } catch (IOException error) {
                throw failure("cannot create " + target, error);
            }
        }
    }

    ShellFileInfo rename(
            final String absolutePath, final String requestedName) {
        final Path source = ShellFilePathPolicy.mutableEntry(absolutePath);
        final Path target = source.resolveSibling(
                ShellFileNamePolicy.validate(requestedName));
        try {
            if (!source.equals(target)) {
                Files.move(source, target);
            }
            return toInfo(target);
        } catch (IOException error) {
            throw failure("cannot rename " + source, error);
        }
    }

    long startOperation(
            final int operation,
            final String[] sourcePaths,
            final String destinationDirectory,
            final IFileOperationCallback callback,
            final IBinder ownerToken) {
        if (operation != OPERATION_DELETE
                && operation != OPERATION_COPY
                && operation != OPERATION_MOVE) {
            throw new IllegalArgumentException("unknown file operation");
        }
        if (sourcePaths == null || sourcePaths.length == 0) {
            throw new IllegalArgumentException("missing source paths");
        }
        if (callback == null || ownerToken == null) {
            throw new IllegalArgumentException("missing operation owner");
        }
        final List<Path> sources = new ArrayList<>(sourcePaths.length);
        for (final String sourcePath : sourcePaths) {
            sources.add(operation == OPERATION_COPY
                    ? ShellFilePathPolicy.existing(sourcePath)
                    : ShellFilePathPolicy.mutableEntry(sourcePath));
        }
        final Path destination;
        if (operation == OPERATION_DELETE) {
            destination = null;
        } else {
            destination = ShellFilePathPolicy.absolute(destinationDirectory);
            if (!Files.isDirectory(destination)) {
                throw new IllegalArgumentException(
                        "destination is not a directory");
            }
        }
        final long id = mNextOperationId.getAndIncrement();
        final FileOperation fileOperation = new FileOperation(
                id, operation, sources, destination,
                callback, ownerToken);
        try {
            ownerToken.linkToDeath(fileOperation, 0);
        } catch (RemoteException error) {
            throw failure("file operation owner is unavailable", error);
        }
        mOperations.put(Long.valueOf(id), fileOperation);
        mOperationExecutor.execute(fileOperation);
        return id;
    }

    void cancel(final long operationId) {
        final FileOperation operation = mOperations.get(
                Long.valueOf(operationId));
        if (operation != null) {
            operation.cancel();
        }
    }

    @Override
    public void close() {
        for (final FileOperation operation : mOperations.values()) {
            operation.cancel();
        }
        mOperationExecutor.shutdownNow();
        mOperations.clear();
    }

    private final class FileOperation
            implements Runnable, IBinder.DeathRecipient {
        final long id;
        final int operation;
        final List<Path> sources;
        final Path destination;
        final IFileOperationCallback callback;
        final IBinder ownerToken;
        final AtomicBoolean cancelled = new AtomicBoolean();
        long bytesCompleted;
        long nextProgressBytes = PROGRESS_BYTE_INTERVAL;
        int currentItemIndex;

        FileOperation(
                final long id,
                final int operation,
                final List<Path> sources,
                final Path destination,
                final IFileOperationCallback callback,
                final IBinder ownerToken) {
            this.id = id;
            this.operation = operation;
            this.sources = sources;
            this.destination = destination;
            this.callback = callback;
            this.ownerToken = ownerToken;
        }

        @Override
        public void run() {
            boolean successful = false;
            String message = "Completed";
            try {
                for (int index = 0; index < sources.size(); index++) {
                    checkCancelled();
                    currentItemIndex = index;
                    final Path source = sources.get(index);
                    notifyProgress(index, source);
                    if (operation == OPERATION_DELETE) {
                        FileTreeDeletion.delete(source, this::checkCancelled);
                    } else {
                        final Path requestedTarget = destination.resolve(
                                source.getFileName());
                        if (operation == OPERATION_MOVE
                                && requestedTarget.toAbsolutePath().normalize()
                                        .equals(source.toAbsolutePath()
                                                .normalize())) {
                            throw new IllegalArgumentException(
                                    "source and destination are the same");
                        }
                        final Path target = availableTarget(requestedTarget);
                        ShellFilePathPolicy.rejectRecursiveTarget(
                                source, target);
                        try {
                            if (operation == OPERATION_COPY) {
                                copyTree(source, target, this);
                            } else {
                                moveTree(source, target, this);
                            }
                        } catch (IOException | RuntimeException error) {
                            if (!(error instanceof MoveSourceCleanupException)
                                    && Files.exists(target, NO_FOLLOW)) {
                                try {
                                    FileTreeDeletion.delete(target, null);
                                } catch (IOException cleanupError) {
                                    error.addSuppressed(cleanupError);
                                }
                            }
                            throw error;
                        }
                    }
                    notifyProgress(index + 1, source);
                }
                successful = true;
            } catch (OperationCancelled error) {
                message = "Cancelled";
            } catch (IOException | RuntimeException error) {
                message = usefulMessage(error);
            } finally {
                mOperations.remove(Long.valueOf(id), this);
                ownerToken.unlinkToDeath(this, 0);
                try {
                    callback.onFinished(id, successful, message);
                } catch (RemoteException ignored) {
                    // The UI owner has gone away.
                }
            }
        }

        @Override
        public void binderDied() {
            cancel();
        }

        void cancel() {
            cancelled.set(true);
        }

        void checkCancelled() throws OperationCancelled {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new OperationCancelled();
            }
        }

        void addBytes(final int count, final Path current)
                throws OperationCancelled {
            bytesCompleted += count;
            if (bytesCompleted >= nextProgressBytes) {
                notifyProgress(currentItemIndex, current);
                nextProgressBytes = bytesCompleted + PROGRESS_BYTE_INTERVAL;
            }
            checkCancelled();
        }

        void notifyProgress(final int completed, final Path current)
                throws OperationCancelled {
            try {
                callback.onProgress(
                        id,
                        completed,
                        sources.size(),
                        current.toString(),
                        bytesCompleted);
            } catch (RemoteException error) {
                cancel();
                throw new OperationCancelled();
            }
        }
    }

    private static ShellFileInfo toInfo(final Path path) throws IOException {
        final BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, NO_FOLLOW);
        final boolean symbolicLink = attributes.isSymbolicLink();
        final boolean directory = attributes.isDirectory()
                || (symbolicLink && Files.isDirectory(path));
        final Path fileName = path.getFileName();
        final String name = fileName == null ? path.toString()
                : fileName.toString();
        final String linkTarget = symbolicLink
                ? Files.readSymbolicLink(path).toString() : "";
        StructStat stat;
        try {
            stat = Os.stat(path.toString());
        } catch (ErrnoException error) {
            if (!symbolicLink) {
                throw new IOException("cannot stat " + path, error);
            }
            try {
                // Keep broken links visible so they can be renamed or
                // deleted. Opening one still fails normally.
                stat = Os.lstat(path.toString());
            } catch (ErrnoException linkError) {
                linkError.addSuppressed(error);
                throw new IOException("cannot stat " + path, linkError);
            }
        }
        return new ShellFileInfo(
                path.toAbsolutePath().normalize().toString(),
                name.length() == 0 ? "/" : name,
                directory ? DocumentsContract.Document.MIME_TYPE_DIR
                        : mimeType(name),
                linkTarget,
                attributes.lastModifiedTime().toMillis(),
                directory ? 0L : attributes.size(),
                stat.st_dev,
                stat.st_ino,
                stat.st_uid,
                stat.st_gid,
                stat.st_mode,
                directory,
                symbolicLink,
                Files.isReadable(path),
                Files.isWritable(path),
                Files.isExecutable(path),
                hidden(path, name));
    }

    static Comparator<ShellFileInfo> comparator(
            final int sortMode, final boolean ascending) {
        final Comparator<ShellFileInfo> valueComparator;
        if (sortMode == SORT_MODIFIED) {
            valueComparator = Comparator.comparingLong(value -> value.modified);
        } else if (sortMode == SORT_SIZE) {
            valueComparator = Comparator.comparingLong(value -> value.size);
        } else {
            valueComparator = (left, right) ->
                    left.name.compareToIgnoreCase(right.name);
        }
        return (left, right) -> {
            if (left.directory != right.directory) {
                return left.directory ? -1 : 1;
            }
            final int compared = valueComparator.compare(left, right);
            return ascending ? compared : -compared;
        };
    }

    static Path availableTarget(final Path requested) {
        if (!Files.exists(requested, NO_FOLLOW)) {
            return requested;
        }
        final String name = requested.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final boolean hasExtension = dot > 0 && dot < name.length() - 1;
        final String stem = hasExtension ? name.substring(0, dot) : name;
        final String extension = hasExtension ? name.substring(dot) : "";
        for (int suffix = 2; ; suffix++) {
            final Path candidate = requested.resolveSibling(
                    stem + " (" + suffix + ")" + extension);
            if (!Files.exists(candidate, NO_FOLLOW)) {
                return candidate;
            }
        }
    }

    private static void moveTree(
            final Path source,
            final Path target,
            final FileOperation operation) throws IOException {
        operation.checkCancelled();
        try {
            Files.move(source, target);
        } catch (IOException directMoveFailure) {
            copyTree(source, target, operation);
            try {
                FileTreeDeletion.delete(source, operation::checkCancelled);
            } catch (IOException deleteFailure) {
                throw new MoveSourceCleanupException(
                        "copied to " + target
                                + " but could not fully remove " + source,
                        deleteFailure,
                        directMoveFailure);
            }
        }
    }

    private static void copyTree(
            final Path source,
            final Path target,
            final FileOperation operation) throws IOException {
        if (Files.isSymbolicLink(source)) {
            Files.createSymbolicLink(target, Files.readSymbolicLink(source));
            return;
        }
        if (!Files.isDirectory(source, NO_FOLLOW)) {
            copyFile(source, target, operation);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    final Path directory,
                    final BasicFileAttributes attributes) throws IOException {
                operation.checkCancelled();
                final Path relative = source.relativize(directory);
                final Path copy = target.resolve(relative);
                Files.createDirectory(copy);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    final Path file,
                    final BasicFileAttributes attributes) throws IOException {
                operation.checkCancelled();
                final Path copy = target.resolve(source.relativize(file));
                if (attributes.isSymbolicLink()) {
                    Files.createSymbolicLink(
                            copy, Files.readSymbolicLink(file));
                } else {
                    copyFile(file, copy, operation);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyFile(
            final Path source,
            final Path target,
            final FileOperation operation) throws IOException {
        try (InputStream input = Files.newInputStream(source);
                OutputStream output = Files.newOutputStream(target)) {
            final byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                operation.addBytes(count, source);
            }
        }
        try {
            Files.setLastModifiedTime(
                    target, Files.getLastModifiedTime(source, NO_FOLLOW));
        } catch (IOException ignored) {
            // Content is more important than optional timestamp preservation.
        }
    }

    private static String mimeType(final String name) {
        final int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            final String mapped = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(
                            name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (mapped != null) {
                return mapped;
            }
        }
        return "application/octet-stream";
    }

    private static boolean hidden(final Path path, final String name) {
        try {
            return Files.isHidden(path);
        } catch (IOException ignored) {
            return name.startsWith(".");
        }
    }

    private static IllegalStateException failure(
            final String message, final Throwable cause) {
        return new IllegalStateException(message + ": "
                + usefulMessage(cause), cause);
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private static final class OperationCancelled extends IOException {
        OperationCancelled() {
            super("file operation cancelled");
        }
    }

    private static final class MoveSourceCleanupException
            extends IOException {
        MoveSourceCleanupException(
                final String message,
                final IOException cause,
                final IOException directMoveFailure) {
            super(message, cause);
            addSuppressed(directMoveFailure);
        }
    }
}
