package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.Binder;
import android.os.IBinder;

import java.util.List;

final class FileManagerOperationController implements AutoCloseable {
    interface Listener {
        void onOperationFinished(
                boolean successful,
                String message);
    }

    private static final long LOCAL_IMPORT = -2L;

    private final Activity mActivity;
    private final Listener mListener;
    private final IBinder mOwnerToken = new Binder();
    private final FileOperationCenter mCenter = FileOperationCenter.get();
    private final FileOperationCenter.Listener mCenterListener =
            this::onRemoteStateChanged;

    private long mActiveOperationId;
    private long mLastCompletionSequence;
    private volatile boolean mImportCancelled;
    private volatile boolean mClosed;
    private ProgressDialog mProgress;

    FileManagerOperationController(
            final Activity activity,
            final Listener listener) {
        mActivity = activity;
        mListener = listener;
        final FileOperationCenter.Snapshot snapshot = mCenter.snapshot();
        mLastCompletionSequence = snapshot.sequence;
        mCenter.addListener(mCenterListener);
        if (snapshot.isBusy()) {
            onRemoteStateChanged(snapshot);
        }
    }

    boolean isBusy() {
        return mActiveOperationId == LOCAL_IMPORT
                || mCenter.snapshot().isBusy();
    }

    IBinder ownerToken() {
        return mOwnerToken;
    }

    boolean startRemote(
            final int operation,
            final List<String> paths,
            final String destination,
            final long clipboardGeneration) {
        if (mClosed || isBusy() || paths == null || paths.isEmpty()) {
            return false;
        }
        return mCenter.start(
                operation,
                paths,
                destination,
                clipboardGeneration);
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
            mActiveOperationId = 0L;
            mImportCancelled = false;
            dismissProgress();
        }
    }

    @Override
    public void close() {
        mClosed = true;
        mCenter.removeListener(mCenterListener);
        if (mActiveOperationId == LOCAL_IMPORT) {
            mImportCancelled = true;
        }
        mActiveOperationId = 0L;
        dismissProgress();
    }

    private void onRemoteStateChanged(
            final FileOperationCenter.Snapshot snapshot) {
        if (mClosed || mActiveOperationId == LOCAL_IMPORT) {
            return;
        }
        if (snapshot.isBusy()) {
            showProgressIfMissing(snapshot.totalItems);
            mProgress.setMax(Math.max(1, snapshot.totalItems));
            mProgress.setProgress(Math.min(
                    snapshot.completedItems, snapshot.totalItems));
            mProgress.setMessage(mActivity.getString(
                    R.string.file_manager_operation_progress,
                    snapshot.completedItems,
                    snapshot.totalItems) + (snapshot.currentPath.isEmpty()
                            ? "" : "\n" + snapshot.currentPath));
            return;
        }
        dismissProgress();
        if (snapshot.state != FileOperationCenter.State.FINISHED
                || snapshot.sequence <= mLastCompletionSequence) {
            return;
        }
        mLastCompletionSequence = snapshot.sequence;
        mListener.onOperationFinished(
                snapshot.successful,
                snapshot.message);
    }

    private void cancel() {
        if (mActiveOperationId == LOCAL_IMPORT) {
            mImportCancelled = true;
            return;
        }
        mCenter.cancel();
    }

    private void showProgressIfMissing(final int total) {
        if (mProgress == null) {
            showProgress(total);
        }
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

    private void dismissProgress() {
        if (mProgress != null) {
            mProgress.dismiss();
            mProgress = null;
        }
    }
}
