package io.github.mekhontsev.magicdesk;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.DragAndDropPermissions;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "MagicDeskDesktopFolder"));
    private final ContentRequestScope mImports = new ContentRequestScope(mExecutor);
    private FileManagerOperationController mOperations;
    private int mLoadGeneration;
    private int mThumbnailLimit;
    private volatile boolean mStarted;
    private boolean mLoaded;
    private volatile boolean mReleased;
    private ShellDesktopFolderHandle mObserverHandle;
    private volatile int mObserverGeneration;
    private boolean mObservedStateChanged;
    private boolean mObservedWallpaperChanged;
    private final Runnable mObservedRefresh = () -> {
        if (!mReleased && mStarted) {
            refresh(true, mThumbnailLimit);
        }
    };
    private final Runnable mObservedMetadataRefresh = () -> {
        final int generation = mObserverGeneration;
        if (!isCurrentObserver(generation)) {
            return;
        }
        final boolean reloadState = mObservedStateChanged;
        final boolean wallpaperChanged = mObservedWallpaperChanged;
        mObservedStateChanged = false;
        mObservedWallpaperChanged = false;
        mExecutor.execute(() -> {
            if (!isCurrentObserver(generation)) {
                return;
            }
            final boolean stateChanged = reloadState && DesktopStateStore.reload();
            mHandler.post(() -> {
                if (isCurrentObserver(generation)) {
                    notifyMetadataChanged(
                            stateChanged, wallpaperChanged);
                }
            });
        });
    };

    private IDesktopFolderObserverCallback observerCallback(final int generation) {
        return new IDesktopFolderObserverCallback.Stub() {
            @Override
            public void onDesktopFolderChanged(final String relativePath) {
                mHandler.post(() -> {
                    if (!isCurrentObserver(generation)) {
                        return;
                    }
                    if (relativePath != null
                            && (relativePath.equals(ShellDesktopDirectory.METADATA_DIRECTORY)
                                    || relativePath.startsWith(
                                            ShellDesktopDirectory.METADATA_DIRECTORY + "/"))) {
                        scheduleMetadataRefresh(relativePath);
                    } else {
                        mHandler.removeCallbacks(mObservedRefresh);
                        mHandler.postDelayed(mObservedRefresh, CHANGE_DEBOUNCE_MILLIS);
                    }
                });
            }
        };
    }

    private boolean isCurrentObserver(final int generation) {
        return !mReleased && mStarted && generation == mObserverGeneration;
    }

    private void scheduleMetadataRefresh(final String relativePath) {
        final String statePath = ShellDesktopDirectory.STATE_RELATIVE_PATH;
        final String wallpaperPath =
                ShellDesktopDirectory.WALLPAPER_RELATIVE_PATH;
        if (relativePath.equals(ShellDesktopDirectory.METADATA_DIRECTORY)) {
            mObservedStateChanged = true;
            mObservedWallpaperChanged = true;
        } else if (relativePath.equals(statePath)) {
            mObservedStateChanged = true;
        } else if (relativePath.equals(wallpaperPath)) {
            mObservedWallpaperChanged = true;
        } else {
            return;
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
        operations();
        ensureObserver();
        refresh(!mLoaded, mThumbnailLimit);
    }

    void stop() {
        mStarted = false;
        mLoadGeneration++;
        mLoaded = false;
        if (mOperations != null) {
            mOperations.close();
            mOperations = null;
        }
        closeObserver();
        mHandler.removeCallbacks(mObservedRefresh);
        mHandler.removeCallbacks(mObservedMetadataRefresh);
        mObservedStateChanged = false;
        mObservedWallpaperChanged = false;
    }

    void release() {
        mReleased = true;
        stop();
        mHandler.removeCallbacksAndMessages(null);
        mImports.close();
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

    void importContent(
            final AndroidContentPayload content,
            final DragAndDropPermissions permissions) {
        importContent(
                content,
                permissions,
                ShellDesktopDirectory.ABSOLUTE_PATH,
                null);
    }

    void importContent(
            final AndroidContentPayload content,
            final DragAndDropPermissions permissions,
            final String destination,
            final String destinationLabel) {
        if (mReleased || content == null || content.isEmpty()) {
            releasePermissions(permissions);
            return;
        }
        submitImport(() -> ContentUriTransfer.prepareContent(
                mActivity.getContentResolver(), content, destination), permissions, destinationLabel);
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
        submitImport(() -> ContentUriTransfer.prepareUris(
                mActivity.getContentResolver(), uris, destination), permissions, destinationLabel);
    }

    private void submitImport(
            final Supplier<ContentImportBatch<?>> prepare,
            final DragAndDropPermissions permissions, final String destinationLabel) {
        final ContentImportBatch<?> request;
        try {
            request = prepare.get();
        } catch (RuntimeException error) {
            releasePermissions(permissions);
            postOperationFailure(error);
            return;
        }
        mImports.submit(cancelled -> request.run(cancelled, null), () -> releasePermissions(permissions))
                .thenAccept(completion -> {
            final ContentImportBatch.Result completed = request.finish(completion.value, completion.failure);
            if (!mReleased) {
                mHandler.post(() -> onImportCompleted(completed, destinationLabel));
            }
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
        if (!operations().startRemote(
                copy ? ShellFileSystem.OPERATION_COPY : ShellFileSystem.OPERATION_MOVE,
                paths, destination, copy ? -1L : clipboardGeneration)) {
            mActivity.setStatus(R.string.file_manager_operation_busy);
            return;
        }
        mActivity.setStatus(mActivity.getString(R.string.file_manager_operation_running)
                + (destinationLabel == null ? "" : " [" + destinationLabel + "]"));
    }

    private FileManagerOperationController operations() {
        if (mOperations == null) {
            mOperations = new FileManagerOperationController(mActivity, this::onTransferCompleted);
        }
        return mOperations;
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
                    observerCallback(generation),
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
            final ContentImportBatch.Result result,
            final String destinationLabel) {
        if (mReleased) {
            return;
        }
        if (result.cancelled && result.firstFailure == null) {
            mActivity.setStatus(mActivity.getResources().getQuantityString(
                    R.plurals.file_import_cancelled, result.total, result.copied, result.total));
        } else if (result.isComplete()) {
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
                    ? new IOException("imported file could not be copied")
                    : result.firstFailure;
            final String message = result.cancelled
                    ? mActivity.getResources().getQuantityString(
                            R.plurals.file_import_cancelled, result.total, result.copied, result.total)
                            + "\n" + ShellAccess.usefulMessage(error)
                    : result.copied == 0
                    ? mActivity.getString(
                            R.string.status_desktop_file_operation_failed,
                            ShellAccess.usefulMessage(error))
                    : mActivity.getResources().getQuantityString(
                            R.plurals.status_desktop_files_partially_copied,
                            result.total,
                            Integer.valueOf(result.copied),
                            Integer.valueOf(result.total));
            mActivity.setErrorStatus(
                    "FILES-005",
                    message,
                    "copied=" + result.copied + " failed=" + result.failed
                            + " skipped=" + result.skipped,
                    error);
        }
        if (result.copied > 0) {
            refresh(true, mThumbnailLimit);
        }
    }

    private void onTransferCompleted(
            final boolean successful,
            final String message) {
        if (mReleased) {
            return;
        }
        if (!successful) {
            postOperationFailure(new IOException(message));
            return;
        }
        mActivity.setStatus(R.string.status_desktop_file_operation_complete);
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
