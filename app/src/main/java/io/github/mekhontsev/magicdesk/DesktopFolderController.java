package io.github.mekhontsev.magicdesk;

import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.DragAndDropPermissions;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class DesktopFolderController {
    private static final String TAG = "MagicDeskFolder";
    private static final long CHANGE_DEBOUNCE_MILLIS = 200L;

    interface Listener {
        void onFilesChanged(List<DesktopFile> files, boolean successfulRead);
    }

    interface MetadataListener {
        void onMetadataChanged(
                boolean stateChanged, boolean wallpaperChanged);
    }

    private final DesktopShellActivity mActivity;
    private final DesktopFileRepository mFilesRepository;
    private final Listener mListener;
    private final MetadataListener mMetadataListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final IBinder mFileOperationOwner = new Binder();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "MagicDeskDesktopFolder"));
    private int mLoadGeneration;
    private int mThumbnailLimit;
    private boolean mStarted;
    private boolean mLoaded;
    private volatile boolean mReleased;
    private ShellDesktopFolderHandle mObserverHandle;
    private int mObserverGeneration;
    private boolean mObservedStateChanged;
    private boolean mObservedWallpaperChanged;
    private final IDesktopFolderObserverCallback mObserverCallback =
            new IDesktopFolderObserverCallback.Stub() {
                @Override
                public void onDesktopFolderChanged(
                        final String relativePath) {
                    if (mReleased) {
                        return;
                    }
                    if (relativePath != null
                            && (relativePath.equals(
                                    ShellDesktopDirectory.METADATA_DIRECTORY)
                                    || relativePath.startsWith(
                                            ShellDesktopDirectory
                                                    .METADATA_DIRECTORY
                                                    + "/"))) {
                        mHandler.post(() ->
                                scheduleMetadataRefresh(relativePath));
                    } else {
                        mHandler.removeCallbacks(mObservedRefresh);
                        mHandler.postDelayed(
                                mObservedRefresh, CHANGE_DEBOUNCE_MILLIS);
                    }
                }
            };
    private final Runnable mObservedRefresh =
            () -> refresh(true, mThumbnailLimit);
    private final Runnable mObservedMetadataRefresh = () -> {
        if (mReleased) {
            return;
        }
        final boolean reloadState = mObservedStateChanged;
        final boolean wallpaperChanged = mObservedWallpaperChanged;
        mObservedStateChanged = false;
        mObservedWallpaperChanged = false;
        mExecutor.execute(() -> {
            final DesktopStateStore.ExternalSnapshot snapshot = reloadState
                    ? DesktopStateStore.readExternal() : null;
            mHandler.post(() -> {
                if (!mReleased) {
                    final boolean stateChanged =
                            DesktopStateStore.applyExternal(snapshot);
                    notifyMetadataChanged(
                            stateChanged, wallpaperChanged);
                }
            });
        });
    };

    private void scheduleMetadataRefresh(final String relativePath) {
        final String statePath = ShellDesktopDirectory.STATE_RELATIVE_PATH;
        final String wallpaperPath =
                ShellDesktopDirectory.WALLPAPER_RELATIVE_PATH;
        if (relativePath.equals(ShellDesktopDirectory.METADATA_DIRECTORY)) {
            mObservedStateChanged = true;
            mObservedWallpaperChanged = true;
        } else if (relativePath.equals(statePath)
                || relativePath.startsWith(statePath + ".")) {
            mObservedStateChanged = true;
        } else if (relativePath.equals(wallpaperPath)
                || relativePath.startsWith(wallpaperPath + ".")) {
            mObservedWallpaperChanged = true;
        }
        mHandler.removeCallbacks(mObservedMetadataRefresh);
        mHandler.postDelayed(
                mObservedMetadataRefresh, CHANGE_DEBOUNCE_MILLIS);
    }

    private void notifyMetadataChanged(
            final boolean stateChanged,
            final boolean wallpaperChanged) {
        mMetadataListener.onMetadataChanged(
                stateChanged, wallpaperChanged);
    }

    DesktopFolderController(
            final DesktopShellActivity activity,
            final DesktopFileRepository filesRepository,
            final Listener listener,
            final MetadataListener metadataListener) {
        mActivity = activity;
        mFilesRepository = filesRepository;
        mListener = listener;
        mMetadataListener = metadataListener;
    }

    void start() {
        if (mReleased) {
            return;
        }
        mStarted = true;
        ensureObserver();
        refresh(!mLoaded, mThumbnailLimit);
    }

    void stop() {
        mStarted = false;
        closeObserver();
        mHandler.removeCallbacks(mObservedRefresh);
        mHandler.removeCallbacks(mObservedMetadataRefresh);
    }

    void release() {
        mReleased = true;
        stop();
        mLoadGeneration++;
        mHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
    }

    void refresh(final boolean force, final int thumbnailLimit) {
        if (mReleased) {
            return;
        }
        mThumbnailLimit = Math.max(0, thumbnailLimit);
        if (!force && mLoaded) {
            return;
        }
        ensureObserver();
        final int generation = ++mLoadGeneration;
        final int requestedThumbnailLimit = mThumbnailLimit;
        mExecutor.execute(() -> {
            final List<DesktopFile> files;
            try {
                files = mFilesRepository.load(requestedThumbnailLimit);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Cannot load desktop directory", error);
                postFailure(generation, error);
                return;
            }
            mActivity.runOnUiThread(() -> {
                if (generation != mLoadGeneration
                        || mActivity.isActivityUnavailable()) {
                    return;
                }
                mLoaded = true;
                mListener.onFilesChanged(files, true);
            });
        });
    }

    void create(
            final String name,
            final boolean directory,
            final Consumer<DesktopFileInfo> completed) {
        if (mReleased) {
            return;
        }
        executeOperation(
                () -> ShellAccess.createDesktopEntry(name, directory),
                completed);
    }

    void createApplicationShortcut(
            final DesktopApplicationShortcut shortcut,
            final Consumer<DesktopFileInfo> completed) {
        if (mReleased || shortcut == null) {
            return;
        }
        executeOperation(
                () -> DesktopEntryFile.createApplication(shortcut),
                completed);
    }

    void rename(
            final DesktopFile file,
            final String newName,
            final Consumer<DesktopFileInfo> completed) {
        if (mReleased) {
            return;
        }
        executeOperation(
                () -> ShellAccess.renameDesktopEntry(
                        file.relativePath, newName),
                completed);
    }

    void delete(final DesktopFile file, final Runnable completed) {
        if (mReleased) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                ShellAccess.deleteDesktopEntry(file.relativePath);
            } catch (IOException | RuntimeException error) {
                postOperationFailure(error);
                return;
            }
            mHandler.post(() -> {
                if (mReleased) {
                    return;
                }
                if (completed != null) {
                    completed.run();
                }
                refresh(true, mThumbnailLimit);
            });
        });
    }

    void importFiles(
            final List<Uri> uris,
            final DragAndDropPermissions permissions) {
        importFiles(
                uris,
                permissions,
                ShellDesktopDirectory.ABSOLUTE_PATH,
                null);
    }

    void importFiles(
            final List<Uri> uris,
            final DragAndDropPermissions permissions,
            final String destination,
            final String destinationLabel) {
        if (mReleased || uris == null || uris.isEmpty()) {
            releasePermissions(permissions);
            return;
        }
        mExecutor.execute(() -> {
            DesktopFileRepository.ImportResult result;
            try {
                result = mFilesRepository.importFiles(uris, destination);
            } catch (IOException | RuntimeException error) {
                result = new DesktopFileRepository.ImportResult(
                        0, uris.size(), error);
            } finally {
                releasePermissions(permissions);
            }
            final DesktopFileRepository.ImportResult completed = result;
            mHandler.post(() -> onImportCompleted(
                    uris.size(), completed, destinationLabel));
        });
    }

    void transferPaths(
            final List<String> paths, final boolean copy) {
        transferPaths(paths, copy, -1L);
    }

    void transferPaths(
            final List<String> paths,
            final boolean copy,
            final long clipboardGeneration) {
        transferPaths(
                paths,
                copy,
                clipboardGeneration,
                ShellDesktopDirectory.ABSOLUTE_PATH,
                null);
    }

    void transferPaths(
            final List<String> paths,
            final boolean copy,
            final String destination,
            final String destinationLabel) {
        transferPaths(paths, copy, -1L, destination, destinationLabel);
    }

    private void transferPaths(
            final List<String> paths,
            final boolean copy,
            final long clipboardGeneration,
            final String destination,
            final String destinationLabel) {
        if (mReleased || paths == null || paths.isEmpty()) {
            return;
        }
        final IFileOperationCallback callback =
                new IFileOperationCallback.Stub() {
                    @Override
                    public void onProgress(
                            final long operationId,
                            final int completedItems,
                            final int totalItems,
                            final String currentPath,
                            final long bytesCompleted) {
                        // The desktop observer owns incremental refreshes.
                    }

                    @Override
                    public void onFinished(
                            final long operationId,
                            final boolean successful,
                            final String message) {
                        mHandler.post(() -> onTransferCompleted(
                                paths.size(),
                                copy,
                                clipboardGeneration,
                                destinationLabel,
                                successful,
                                message));
                    }
                };
        mExecutor.execute(() -> {
            try {
                ShellAccess.startShellFileOperation(
                        copy
                                ? ShellFileSystem.OPERATION_COPY
                                : ShellFileSystem.OPERATION_MOVE,
                        paths.toArray(new String[0]),
                        destination,
                        callback,
                        mFileOperationOwner);
            } catch (IOException | RuntimeException error) {
                postOperationFailure(error);
            }
        });
    }

    void inspect(
            final DesktopFile file,
            final Consumer<ShellFileInfo> completed) {
        if (mReleased || file == null || completed == null) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                final ShellFileInfo info = ShellAccess.getShellFileInfo(
                        absolutePath(file));
                mHandler.post(() -> {
                    if (!mReleased) {
                        completed.accept(info);
                    }
                });
            } catch (IOException | RuntimeException error) {
                postOperationFailure(error);
            }
        });
    }

    void installApk(final DesktopFile file) {
        if (mReleased || file == null) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                ShellAccess.run(ShellPackageInstaller.command(
                        absolutePath(file)));
                mHandler.post(() -> {
                    if (!mReleased) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.file_manager_install_complete,
                                file.name));
                    }
                });
            } catch (IOException | RuntimeException error) {
                postOperationFailure(error);
            }
        });
    }

    void setWallpaper(final DesktopFile file) {
        if (mReleased || file == null) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                final ShellFileInfo info = ShellAccess.getShellFileInfo(
                        absolutePath(file));
                DesktopWallpaperFileAction.apply(info);
                mHandler.post(() -> {
                    if (!mReleased) {
                        mActivity.setStatus(R.string
                                .status_desktop_wallpaper_changed);
                    }
                });
            } catch (IOException | RuntimeException error) {
                postOperationFailure(error);
            }
        });
    }

    private void executeOperation(
            final FileOperation operation,
            final Consumer<DesktopFileInfo> completed) {
        mExecutor.execute(() -> {
            final DesktopFileInfo result;
            try {
                result = operation.run();
            } catch (IOException | RuntimeException error) {
                postOperationFailure(error);
                return;
            }
            mHandler.post(() -> {
                if (mReleased) {
                    return;
                }
                if (completed != null) {
                    completed.accept(result);
                }
                refresh(true, mThumbnailLimit);
            });
        });
    }

    private void ensureObserver() {
        if (mReleased
                || !mStarted
                || mObserverHandle != null
                || !ShellAccess.isReady()) {
            return;
        }
        final int generation = ++mObserverGeneration;
        try {
            mObserverHandle = ShellAccess.openDesktopFolderObserver(
                    mObserverCallback,
                    () -> mHandler.post(() -> {
                        if (!mReleased
                                && generation == mObserverGeneration) {
                            mObserverHandle = null;
                        }
                    }));
        } catch (IOException error) {
            Log.d(TAG, "Desktop directory observation unavailable", error);
        }
    }

    private void closeObserver() {
        mObserverGeneration++;
        final ShellDesktopFolderHandle handle = mObserverHandle;
        mObserverHandle = null;
        if (handle != null) {
            handle.close();
        }
    }

    private void postFailure(final int generation, final Throwable error) {
        mActivity.runOnUiThread(() -> {
            if (mReleased
                    || generation != mLoadGeneration
                    || mActivity.isActivityUnavailable()) {
                return;
            }
            mLoaded = false;
            mListener.onFilesChanged(Collections.emptyList(), false);
            mActivity.setErrorStatus(
                    "FILES-002",
                    mActivity.getString(
                            R.string.status_desktop_folder_failed,
                            ShellAccess.usefulMessage(error)),
                    "path=" + ShellDesktopDirectory.ABSOLUTE_PATH,
                    error);
        });
    }

    private void postOperationFailure(final Throwable error) {
        mActivity.runOnUiThread(() -> {
            if (mReleased) {
                return;
            }
            mActivity.setErrorStatus(
                    "FILES-004",
                    mActivity.getString(
                            R.string.status_desktop_file_operation_failed,
                            ShellAccess.usefulMessage(error)),
                    "path=" + ShellDesktopDirectory.ABSOLUTE_PATH,
                    error);
        });
    }

    private void onImportCompleted(
            final int requested,
            final DesktopFileRepository.ImportResult result,
            final String destinationLabel) {
        if (mReleased) {
            return;
        }
        if (result.failed == 0) {
            mActivity.setStatus(destinationLabel == null
                    ? mActivity.getResources().getQuantityString(
                            R.plurals.status_desktop_files_copied,
                            result.copied,
                            Integer.valueOf(result.copied))
                    : mActivity.getResources().getQuantityString(
                            R.plurals.status_shortcut_files_copied,
                            result.copied,
                            Integer.valueOf(result.copied),
                            destinationLabel));
        } else {
            final Throwable error = result.firstFailure == null
                    ? new IOException("dropped file could not be copied")
                    : result.firstFailure;
            final String message = result.copied == 0
                    ? mActivity.getString(
                            R.string.status_desktop_file_operation_failed,
                            ShellAccess.usefulMessage(error))
                    : mActivity.getResources().getQuantityString(
                            R.plurals.status_desktop_files_partially_copied,
                            requested,
                            Integer.valueOf(result.copied),
                            Integer.valueOf(requested));
            mActivity.setErrorStatus(
                    "FILES-005",
                    message,
                    "copied=" + result.copied + " failed=" + result.failed,
                    error);
        }
        if (result.copied > 0) {
            refresh(true, mThumbnailLimit);
        }
    }

    private void onTransferCompleted(
            final int count,
            final boolean copy,
            final long clipboardGeneration,
            final String destinationLabel,
            final boolean successful,
            final String message) {
        if (mReleased) {
            return;
        }
        if (!successful) {
            postOperationFailure(new IOException(message));
            return;
        }
        if (!copy && clipboardGeneration >= 0L) {
            FileManagerClipboard.clearIfGeneration(clipboardGeneration);
        }
        if (destinationLabel == null) {
            mActivity.setStatus(mActivity.getResources().getQuantityString(
                    copy
                            ? R.plurals.status_desktop_items_copied
                            : R.plurals.status_desktop_items_moved,
                    count,
                    Integer.valueOf(count)));
        } else {
            mActivity.setStatus(mActivity.getResources().getQuantityString(
                    copy
                            ? R.plurals.status_shortcut_items_copied
                            : R.plurals.status_shortcut_items_moved,
                    count,
                    Integer.valueOf(count),
                    destinationLabel));
        }
        refresh(true, mThumbnailLimit);
    }

    private static void releasePermissions(
            final DragAndDropPermissions permissions) {
        if (permissions == null) {
            return;
        }
        try {
            permissions.release();
        } catch (RuntimeException ignored) {
            // The owning activity may already have released the drag grant.
        }
    }

    private static String absolutePath(final DesktopFile file) {
        return ShellDesktopDirectory.ABSOLUTE_PATH
                + "/" + file.relativePath;
    }

    @FunctionalInterface
    private interface FileOperation {
        DesktopFileInfo run() throws IOException;
    }
}
