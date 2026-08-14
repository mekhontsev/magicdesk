package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.Binder;
import android.os.IBinder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;

final class FileManagerOperationController implements AutoCloseable {
    interface Listener {
        void onOperationFinished(
                boolean successful,
                String message,
                boolean movedClipboard);

        void onOperationStartFailed(Throwable error);
    }

    private static final long NO_OPERATION = -1L;
    private static final long LOCAL_IMPORT = -2L;
    private static final long PENDING_REMOTE_OPERATION = 0L;

    private final Activity mActivity;
    private final ExecutorService mWorker;
    private final Listener mListener;
    private final IBinder mOwnerToken = new Binder();
    private final IFileOperationCallback mCallback =
            new IFileOperationCallback.Stub() {
                @Override
                public void onProgress(
                        final long operationId,
                        final int completedItems,
                        final int totalItems,
                        final String currentPath,
                        final long bytesCompleted) {
                    mActivity.runOnUiThread(() -> updateRemoteProgress(
                            operationId,
                            completedItems,
                            totalItems,
                            currentPath));
                }

                @Override
                public void onFinished(
                        final long operationId,
                        final boolean successful,
                        final String message) {
                    mActivity.runOnUiThread(() -> finishRemote(
                            operationId, successful, message));
                }
            };

    private long mActiveOperationId = NO_OPERATION;
    private boolean mMovesClipboard;
    private volatile boolean mImportCancelled;
    private volatile boolean mClosed;
    private ProgressDialog mProgress;

    FileManagerOperationController(
            final Activity activity,
            final ExecutorService worker,
            final Listener listener) {
        mActivity = activity;
        mWorker = worker;
        mListener = listener;
    }

    boolean isBusy() {
        return mActiveOperationId != NO_OPERATION;
    }

    IBinder ownerToken() {
        return mOwnerToken;
    }

    boolean startRemote(
            final int operation,
            final List<String> paths,
            final String destination,
            final boolean movesClipboard) {
        if (mClosed || isBusy() || paths == null || paths.isEmpty()) {
            return false;
        }
        mActiveOperationId = PENDING_REMOTE_OPERATION;
        mMovesClipboard = movesClipboard;
        showProgress(paths.size());
        mWorker.execute(() -> {
            try {
                final long operationId = ShellAccess.startShellFileOperation(
                        operation,
                        paths.toArray(new String[0]),
                        destination,
                        mCallback,
                        mOwnerToken);
                if (mClosed) {
                    ShellAccess.cancelShellFileOperation(operationId);
                    return;
                }
                mActivity.runOnUiThread(() -> {
                    if (mClosed) {
                        return;
                    }
                    if (mActiveOperationId == PENDING_REMOTE_OPERATION) {
                        mActiveOperationId = operationId;
                    }
                });
            } catch (IOException | RuntimeException error) {
                mActivity.runOnUiThread(() -> {
                    if (mClosed) {
                        return;
                    }
                    reset();
                    mListener.onOperationStartFailed(error);
                });
            }
        });
        return true;
    }

    boolean beginImport(final int totalItems) {
        if (mClosed || isBusy()) {
            return false;
        }
        mActiveOperationId = LOCAL_IMPORT;
        mImportCancelled = false;
        showProgress(totalItems);
        return true;
    }

    boolean isImportCancelled() {
        return mImportCancelled || Thread.currentThread().isInterrupted();
    }

    void updateImportProgress(final int completed, final int total) {
        mActivity.runOnUiThread(() -> {
            if (mClosed
                    || mActiveOperationId != LOCAL_IMPORT
                    || mProgress == null) {
                return;
            }
            mProgress.setMax(Math.max(1, total));
            mProgress.setProgress(Math.min(completed, total));
            mProgress.setMessage(mActivity.getString(
                    R.string.file_manager_operation_progress,
                    completed,
                    total));
        });
    }

    void finishImport() {
        if (mActiveOperationId == LOCAL_IMPORT) {
            reset();
        }
    }

    @Override
    public void close() {
        mClosed = true;
        final long operationId = mActiveOperationId;
        if (operationId == LOCAL_IMPORT) {
            mImportCancelled = true;
        } else if (operationId > 0L) {
            try {
                // The binder call only flips the remote cancellation flag.
                ShellAccess.cancelShellFileOperation(operationId);
            } catch (IOException ignored) {
                // Service teardown will stop any remaining operation.
            }
        }
        reset();
    }

    private void updateRemoteProgress(
            final long operationId,
            final int completed,
            final int total,
            final String path) {
        if (mClosed || (mActiveOperationId > 0L
                && operationId != mActiveOperationId)) {
            return;
        }
        mActiveOperationId = operationId;
        if (mProgress != null) {
            mProgress.setMax(Math.max(1, total));
            mProgress.setProgress(Math.min(completed, total));
            mProgress.setMessage(mActivity.getString(
                    R.string.file_manager_operation_progress,
                    completed,
                    total) + "\n" + path);
        }
    }

    private void finishRemote(
            final long operationId,
            final boolean successful,
            final String message) {
        if (mClosed || (mActiveOperationId > 0L
                && operationId != mActiveOperationId)) {
            return;
        }
        final boolean movedClipboard = mMovesClipboard;
        reset();
        mListener.onOperationFinished(
                successful, message, movedClipboard);
    }

    private void cancel() {
        final long operationId = mActiveOperationId;
        if (operationId == LOCAL_IMPORT) {
            mImportCancelled = true;
            return;
        }
        if (operationId <= 0L) {
            return;
        }
        mWorker.execute(() -> {
            try {
                ShellAccess.cancelShellFileOperation(operationId);
            } catch (IOException ignored) {
                // Completion or service shutdown owns the remaining cleanup.
            }
        });
    }

    private void showProgress(final int total) {
        dismissProgress();
        mProgress = new ProgressDialog(mActivity);
        mProgress.setTitle(R.string.file_manager_operation_running);
        mProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mProgress.setIndeterminate(false);
        mProgress.setMax(Math.max(1, total));
        mProgress.setCancelable(false);
        mProgress.setButton(
                ProgressDialog.BUTTON_NEGATIVE,
                mActivity.getString(R.string.file_manager_cancel),
                (dialog, which) -> cancel());
        mProgress.show();
    }

    private void reset() {
        mActiveOperationId = NO_OPERATION;
        mMovesClipboard = false;
        mImportCancelled = false;
        dismissProgress();
    }

    private void dismissProgress() {
        if (mProgress != null) {
            mProgress.dismiss();
            mProgress = null;
        }
    }
}
