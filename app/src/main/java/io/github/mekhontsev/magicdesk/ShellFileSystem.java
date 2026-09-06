package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.FileObserver;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
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
    private static final long PROGRESS_BYTE_INTERVAL = 1024L * 1024L;
    private static final int SEARCH_BATCH_SIZE = 40;
    private static final int MAX_SEARCH_RESULTS = 2000;
    private static final int DIRECTORY_EVENTS = FileObserver.CREATE
            | FileObserver.DELETE
            | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO
            | FileObserver.CLOSE_WRITE
            | FileObserver.ATTRIB
            | FileObserver.DELETE_SELF
            | FileObserver.MOVE_SELF;

    private final AtomicLong mNextOperationId = new AtomicLong(1L);
    private final Map<Long, FileOperation> mOperations =
            new ConcurrentHashMap<>();
    private final Map<Long, FileSearch> mSearches =
            new ConcurrentHashMap<>();
    private final Map<IBinder, DirectoryObserver> mDirectoryObservers =
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
    private final ExecutorService mSearchExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskFileSearch");
                thread.setDaemon(true);
                return thread;
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

    void deleteVerifiedFile(
            final String absolutePath, final long deviceId, final long inode) {
        final Path path = ShellFilePathPolicy.absolute(absolutePath);
        try {
            final StructStat stat = Os.lstat(path.toString());
            if (!OsConstants.S_ISREG(stat.st_mode)
                    || stat.st_dev != deviceId || stat.st_ino != inode) {
                throw new IllegalArgumentException("incomplete file was replaced");
            }
            // Cleanup is immediate and non-recursive, after verifying the
            // original ordinary file, never a queued directory-tree operation.
            Os.remove(path.toString());
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.ENOENT) {
                throw failure("cannot remove incomplete file " + path, error);
            }
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
        try {
            mOperationExecutor.execute(fileOperation);
        } catch (RuntimeException error) {
            mOperations.remove(Long.valueOf(id), fileOperation);
            ownerToken.unlinkToDeath(fileOperation, 0);
            throw error;
        }
        return id;
    }

    void cancel(final long operationId) {
        final FileOperation operation = mOperations.get(
                Long.valueOf(operationId));
        if (operation != null) {
            operation.cancel();
        }
    }

    void startDirectoryObserver(
            final String absolutePath,
            final IShellDirectoryObserverCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("missing directory callback");
        }
        final Path directory = ShellFilePathPolicy.existing(absolutePath);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("path is not a directory");
        }
        final IBinder binder = callback.asBinder();
        final DirectoryObserver observer = new DirectoryObserver(
                directory.toString(), callback);
        try {
            binder.linkToDeath(observer, 0);
        } catch (RemoteException error) {
            throw failure("directory observer owner is unavailable", error);
        }
        final DirectoryObserver previous = mDirectoryObservers.put(
                binder, observer);
        if (previous != null) {
            previous.close();
        }
        observer.startWatching();
    }

    void stopDirectoryObserver(
            final IShellDirectoryObserverCallback callback) {
        if (callback == null) {
            return;
        }
        final DirectoryObserver observer = mDirectoryObservers.remove(
                callback.asBinder());
        if (observer != null) {
            observer.close();
        }
    }

    long startSearch(
            final String rootPath,
            final String rawQuery,
            final boolean showHidden,
            final int requestedMaxResults,
            final IFileSearchCallback callback,
            final IBinder ownerToken) {
        final Path root = ShellFilePathPolicy.existing(rootPath);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("search root is not a directory");
        }
        final String query = rawQuery == null
                ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            throw new IllegalArgumentException("missing search query");
        }
        if (callback == null || ownerToken == null) {
            throw new IllegalArgumentException("missing search owner");
        }
        final int maxResults = Math.max(1,
                Math.min(MAX_SEARCH_RESULTS, requestedMaxResults));
        final long id = mNextOperationId.getAndIncrement();
        final FileSearch search = new FileSearch(
                id, root, query, showHidden, maxResults,
                callback, ownerToken);
        try {
            ownerToken.linkToDeath(search, 0);
        } catch (RemoteException error) {
            throw failure("file search owner is unavailable", error);
        }
        mSearches.put(Long.valueOf(id), search);
        try {
            mSearchExecutor.execute(search);
        } catch (RuntimeException error) {
            mSearches.remove(Long.valueOf(id), search);
            ownerToken.unlinkToDeath(search, 0);
            throw error;
        }
        return id;
    }

    void cancelSearch(final long searchId) {
        final FileSearch search = mSearches.get(Long.valueOf(searchId));
        if (search != null) {
            search.cancel();
        }
    }

    @Override
    public void close() {
        for (final FileOperation operation : mOperations.values()) {
            operation.cancel();
        }
        mOperationExecutor.shutdownNow();
        mOperations.clear();
        for (final FileSearch search : mSearches.values()) {
            search.cancel();
        }
        mSearchExecutor.shutdownNow();
        mSearches.clear();
        for (final DirectoryObserver observer
                : mDirectoryObservers.values()) {
            observer.close();
        }
        mDirectoryObservers.clear();
    }

    private final class DirectoryObserver extends FileObserver
            implements IBinder.DeathRecipient {
        private final String path;
        private final IShellDirectoryObserverCallback callback;
        private final IBinder binder;
        private final AtomicBoolean closed = new AtomicBoolean();

        DirectoryObserver(
                final String path,
                final IShellDirectoryObserverCallback callback) {
            super(Path.of(path).toFile(), DIRECTORY_EVENTS);
            this.path = path;
            this.callback = callback;
            binder = callback.asBinder();
        }

        @Override
        public void onEvent(final int event, final String changedPath) {
            if (closed.get()) {
                return;
            }
            try {
                callback.onDirectoryChanged(path);
            } catch (RemoteException error) {
                binderDied();
            }
        }

        @Override
        public void binderDied() {
            mDirectoryObservers.remove(binder, this);
            close();
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            stopWatching();
            binder.unlinkToDeath(this, 0);
        }
    }

    private final class FileSearch
            implements Runnable, IBinder.DeathRecipient {
        final long id;
        final Path root;
        final String query;
        final boolean showHidden;
        final int maxResults;
        final IFileSearchCallback callback;
        final IBinder ownerToken;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<ShellFileInfo> batch = new ArrayList<>(SEARCH_BATCH_SIZE);
        int resultCount;
        boolean truncated;

        FileSearch(
                final long id,
                final Path root,
                final String query,
                final boolean showHidden,
                final int maxResults,
                final IFileSearchCallback callback,
                final IBinder ownerToken) {
            this.id = id;
            this.root = root;
            this.query = query;
            this.showHidden = showHidden;
            this.maxResults = maxResults;
            this.callback = callback;
            this.ownerToken = ownerToken;
        }

        @Override
        public void run() {
            boolean successful = false;
            String message = "Completed";
            try {
                Files.walkFileTree(
                        root,
                        EnumSet.noneOf(FileVisitOption.class),
                        Integer.MAX_VALUE,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(
                                    final Path directory,
                                    final BasicFileAttributes attributes)
                                    throws IOException {
                                checkCancelled();
                                if (!root.equals(directory)
                                        && !showHidden
                                        && hidden(directory,
                                                directory.getFileName()
                                                        .toString())) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return root.equals(directory)
                                        ? FileVisitResult.CONTINUE
                                        : visit(directory);
                            }

                            @Override
                            public FileVisitResult visitFile(
                                    final Path file,
                                    final BasicFileAttributes attributes)
                                    throws IOException {
                                checkCancelled();
                                return visit(file);
                            }

                            @Override
                            public FileVisitResult visitFileFailed(
                                    final Path file,
                                    final IOException error) {
                                return cancelled.get()
                                        ? FileVisitResult.TERMINATE
                                        : FileVisitResult.CONTINUE;
                            }
                        });
                checkCancelled();
                sendBatch();
                successful = true;
            } catch (OperationCancelled error) {
                message = "Cancelled";
            } catch (IOException | RuntimeException error) {
                message = usefulMessage(error);
            } finally {
                mSearches.remove(Long.valueOf(id), this);
                ownerToken.unlinkToDeath(this, 0);
                try {
                    callback.onFinished(
                            id, successful, truncated, message);
                } catch (RemoteException ignored) {
                    // The search owner has gone away.
                }
            }
        }

        private FileVisitResult visit(final Path path) throws IOException {
            final String name = path.getFileName() == null
                    ? path.toString() : path.getFileName().toString();
            if ((showHidden || !hidden(path, name))
                    && matchesSearchQuery(name, query)) {
                batch.add(toInfo(path));
                resultCount++;
                if (batch.size() >= SEARCH_BATCH_SIZE) {
                    sendBatch();
                }
                if (resultCount >= maxResults) {
                    truncated = true;
                    return FileVisitResult.TERMINATE;
                }
            }
            return FileVisitResult.CONTINUE;
        }

        private void sendBatch() throws OperationCancelled {
            if (batch.isEmpty()) {
                return;
            }
            try {
                callback.onBatch(
                        id, batch.toArray(new ShellFileInfo[0]));
                batch.clear();
            } catch (RemoteException error) {
                cancel();
                throw new OperationCancelled();
            }
            checkCancelled();
        }

        private void checkCancelled() throws OperationCancelled {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new OperationCancelled();
            }
        }

        @Override
        public void binderDied() {
            cancel();
        }

        void cancel() {
            cancelled.set(true);
        }
    }

    private final class FileOperation
            implements Runnable, IBinder.DeathRecipient, FileTreeTransfer.Progress {
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
                        FileTreeTransfer.transfer(
                                source, target, operation == OPERATION_MOVE, this);
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

        @Override
        public void checkCancelled() throws OperationCancelled {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new OperationCancelled();
            }
        }

        @Override
        public void addBytes(final int count, final Path current)
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
            int compared = valueComparator.compare(left, right);
            // Each page enumerates the directory again. Equal primary values
            // need a total name order, independent of filesystem enumeration.
            if (compared == 0) {
                compared = left.name.compareToIgnoreCase(right.name);
            }
            if (compared == 0) {
                compared = left.name.compareTo(right.name);
            }
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

    static boolean matchesSearchQuery(
            final String name, final String normalizedQuery) {
        return name != null
                && normalizedQuery != null
                && !normalizedQuery.isEmpty()
                && name.toLowerCase(Locale.ROOT).contains(normalizedQuery);
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
}
