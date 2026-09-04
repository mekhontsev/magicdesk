package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.net.Uri;
import android.view.DragAndDropPermissions;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;

final class FileManagerImportController {
    interface Listener {
        void onImportFinished(int copied, Throwable firstFailure);
    }

    private final Activity mActivity;
    private final ExecutorService mWorker;
    private final FileManagerOperationController mOperations;
    private final Listener mListener;

    FileManagerImportController(
            final Activity activity,
            final ExecutorService worker,
            final FileManagerOperationController operations,
            final Listener listener) {
        mActivity = activity;
        mWorker = worker;
        mOperations = operations;
        mListener = listener;
    }

    void importFiles(
            final String destination,
            final List<Uri> uris,
            final DragAndDropPermissions permissions) {
        importFiles(destination, uris, permissions, null);
    }

    void importFiles(
            final String destination,
            final List<Uri> uris,
            final DragAndDropPermissions permissions,
            final Runnable completed) {
        if (!mOperations.beginImport(uris.size())) {
            release(permissions);
            run(completed);
            return;
        }
        mWorker.execute(() -> {
            int imported = 0;
            Throwable firstFailure = null;
            try {
                for (final Uri uri : uris) {
                    if (mOperations.isImportCancelled()) {
                        break;
                    }
                    String createdPath = null;
                    try {
                        final String name = safeName(
                                ContentUriTransfer.displayName(
                                        mActivity.getContentResolver(),
                                        uri,
                                        ContentUriTransfer.FALLBACK_FILE_NAME));
                        final ShellFileInfo created =
                                ShellAccess.createAvailableShellEntry(
                                        destination, name, false);
                        createdPath = created.absolutePath;
                        ContentUriTransfer.copyToShellFile(
                                mActivity.getContentResolver(),
                                uri,
                                created,
                                mOperations::isImportCancelled);
                        imported++;
                        mOperations.updateImportProgress(
                                imported, uris.size());
                    } catch (IOException | RuntimeException error) {
                        if (firstFailure == null) {
                            firstFailure = error;
                        }
                        if (createdPath != null) {
                            startCleanupDelete(createdPath);
                        }
                    }
                }
            } finally {
                release(permissions);
                run(completed);
            }
            final int copied = imported;
            final Throwable failure = firstFailure;
            mActivity.runOnUiThread(() -> {
                mOperations.finishImport();
                mListener.onImportFinished(copied, failure);
            });
        });
    }

    void importContent(
            final String destination,
            final AndroidContentPayload content,
            final DragAndDropPermissions permissions) {
        if (content == null || content.isEmpty()) {
            release(permissions);
            return;
        }
        if (content.hasUris()) {
            importFiles(destination, content.uris(), permissions);
            return;
        }
        if (!mOperations.beginImport(1)) {
            release(permissions);
            return;
        }
        mWorker.execute(() -> {
            Throwable failure = null;
            int imported = 0;
            try {
                ContentUriTransfer.importTextToShellDirectory(
                        destination, content);
                imported = 1;
                mOperations.updateImportProgress(1, 1);
            } catch (IOException | RuntimeException error) {
                failure = error;
            } finally {
                release(permissions);
            }
            final int copied = imported;
            final Throwable firstFailure = failure;
            mActivity.runOnUiThread(() -> {
                mOperations.finishImport();
                mListener.onImportFinished(copied, firstFailure);
            });
        });
    }

    static String safeName(final String requested) {
        try {
            return ShellFileNamePolicy.validate(requested);
        } catch (IllegalArgumentException error) {
            return ContentUriTransfer.FALLBACK_FILE_NAME;
        }
    }

    private void startCleanupDelete(final String path) {
        try {
            ShellAccess.startShellFileOperation(
                    ShellFileSystem.OPERATION_DELETE,
                    new String[]{path},
                    "",
                    new IFileOperationCallback.Stub() {
                        @Override
                        public void onProgress(
                                final long id,
                                final int completed,
                                final int total,
                                final String current,
                                final long bytes) {
                        }

                        @Override
                        public void onFinished(
                                final long id,
                                final boolean success,
                                final String message) {
                        }
                    },
                    mOperations.ownerToken());
        } catch (IOException ignored) {
            // An empty failed import remains visible for manual deletion.
        }
    }

    private static void release(
            final DragAndDropPermissions permissions) {
        if (permissions != null) {
            permissions.release();
        }
    }

    private static void run(final Runnable action) {
        if (action != null) {
            action.run();
        }
    }
}
