package io.github.mekhontsev.magicdesk;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

    private final DesktopShellActivity mActivity;
    private final DesktopFileRepository mFilesRepository;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "MagicDeskDesktopFolder"));
    private int mLoadGeneration;
    private int mThumbnailLimit;
    private boolean mStarted;
    private boolean mLoaded;
    private volatile boolean mReleased;
    private ShellDesktopFolderHandle mObserverHandle;
    private final IDesktopFolderObserverCallback mObserverCallback =
            new IDesktopFolderObserverCallback.Stub() {
                @Override
                public void onDesktopFolderChanged() {
                    mHandler.removeCallbacks(mObservedRefresh);
                    mHandler.postDelayed(
                            mObservedRefresh, CHANGE_DEBOUNCE_MILLIS);
                }
            };
    private final Runnable mObservedRefresh =
            () -> refresh(true, mThumbnailLimit);

    DesktopFolderController(
            final DesktopShellActivity activity,
            final DesktopFileRepository filesRepository,
            final Listener listener) {
        mActivity = activity;
        mFilesRepository = filesRepository;
        mListener = listener;
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
        try {
            mObserverHandle = ShellAccess.openDesktopFolderObserver(
                    mObserverCallback,
                    () -> mHandler.post(() -> {
                        if (!mReleased) {
                            mObserverHandle = null;
                        }
                    }));
        } catch (IOException error) {
            Log.d(TAG, "Desktop directory observation unavailable", error);
        }
    }

    private void closeObserver() {
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

    @FunctionalInterface
    private interface FileOperation {
        DesktopFileInfo run() throws IOException;
    }
}
