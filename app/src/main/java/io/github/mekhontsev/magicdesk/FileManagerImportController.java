package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.net.Uri;
import android.view.DragAndDropPermissions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class FileManagerImportController implements AutoCloseable {
    interface Listener {
        void onImportFinished(ContentImportBatch.Result result, Throwable failure);
    }

    private final Activity mActivity;
    private final ExecutorService mWorker;
    private final ContentRequestScope mImports;
    private final FileManagerOperationController mOperations;
    private final Listener mListener;
    private volatile boolean mClosed;
    private boolean mPickerOpening;
    private String mPickerRequestId;

    FileManagerImportController(
            final Activity activity,
            final ExecutorService worker,
            final FileManagerOperationController operations,
            final Listener listener) {
        mActivity = activity;
        mWorker = worker;
        mImports = new ContentRequestScope(worker);
        mOperations = operations;
        mListener = listener;
    }

    void chooseFiles(final String destination, final int displayId) {
        if (mClosed || mPickerOpening || mPickerRequestId != null || mOperations.isBusy()) {
            return;
        }
        mPickerOpening = true;
        try {
            mWorker.execute(() -> {
                if (mClosed) {
                    return;
                }
                try {
                    final JSONObject parameters = new JSONObject()
                            .put("mimeType", "*/*")
                            .put("multiple", true)
                            .put("mode", DesktopLaunchMode.WINDOWED.wireName)
                            .put("instance", DesktopTaskInstancePolicy.CREATE_NEW.wireName);
                    final DesktopAutomationResult launched = new AndroidIntegrationGateway(mActivity)
                            .invokeDesktopAction("open-document", parameters, "files", displayId);
                    final String id = (launched.success ? launched.data : launched.observation)
                            .optString("requestId", "");
                    final Throwable failure = launched.success ? null : new IOException(launched.message);
                    mActivity.runOnUiThread(() -> onPickerLaunched(destination, id, failure));
                } catch (IOException | JSONException | RuntimeException failure) {
                    mActivity.runOnUiThread(() -> onPickerLaunched(destination, "", failure));
                }
            });
        } catch (RejectedExecutionException failure) {
            onPickerLaunched(destination, "", failure);
        }
    }

    private void onPickerLaunched(
            final String destination, final String id, final Throwable failure) {
        mPickerOpening = false;
        if (mClosed || failure != null || id.isEmpty()) {
            AndroidActivityResultStore.discard(id);
            if (!mClosed) {
                mListener.onImportFinished(null, failure != null ? failure
                        : new IOException("file picker returned no result request"));
            }
            return;
        }
        mPickerRequestId = id;
        AndroidActivityResultStore.whenReady(id, () -> readPickerResult(destination, id));
    }

    private void readPickerResult(final String destination, final String id) {
        if (mClosed) {
            return;
        }
        try {
            mWorker.execute(() -> {
                if (mClosed) {
                    return;
                }
                final AndroidActivityResultStore.ClaimedResult owned = AndroidActivityResultStore.claim(id);
                try {
                    if (owned == null) {
                        throw new IOException("file selection result is no longer available");
                    }
                    final JSONObject result = owned.toJson();
                    if (!"completed".equals(result.optString("state"))) {
                        throw new IOException(result.optString("error", "file selection did not complete"));
                    }
                    final List<Uri> uris = result.getInt("resultCode") == Activity.RESULT_OK
                            ? resultUris(result.optJSONObject("data")) : null;
                    if (uris != null && uris.isEmpty()) {
                        throw new IOException("file picker returned no content URI");
                    }
                    mActivity.runOnUiThread(() -> onPickerResult(destination, id, owned, uris, null));
                } catch (IOException | JSONException | RuntimeException failure) {
                    if (owned != null) {
                        owned.close();
                    }
                    mActivity.runOnUiThread(() -> onPickerResult(destination, id, null, null, failure));
                }
            });
        } catch (RejectedExecutionException failure) {
            mActivity.runOnUiThread(() -> onPickerResult(destination, id, null, null, failure));
        }
    }

    private void onPickerResult(
            final String destination, final String id,
            final AndroidActivityResultStore.ClaimedResult owned,
            final List<Uri> uris, final Throwable failure) {
        if (mClosed || !id.equals(mPickerRequestId)) {
            if (owned != null) {
                owned.close();
            }
            AndroidActivityResultStore.discard(id);
            return;
        }
        // Ownership passes to the import: closing Files must not revoke a grant
        // underneath its running provider read.
        mPickerRequestId = null;
        if (failure != null || uris == null) {
            if (owned != null) {
                owned.close();
            }
            AndroidActivityResultStore.discard(id);
            if (failure != null) {
                mListener.onImportFinished(null, failure);
            }
            return;
        }
        try {
            importFiles(destination, uris, null, owned::close);
        } catch (RuntimeException | Error failureToStart) {
            owned.close();
            throw failureToStart;
        }
    }

    private void importFiles(
            final String destination,
            final List<Uri> uris,
            final DragAndDropPermissions permissions,
            final Runnable completed) {
        startImport(() -> ContentUriTransfer.prepareUris(
                mActivity.getContentResolver(), uris, destination), permissions, completed);
    }

    void importContent(
            final String destination,
            final AndroidContentPayload content,
            final DragAndDropPermissions permissions) {
        if (mClosed || content == null || content.isEmpty()) {
            release(permissions, null);
            return;
        }
        startImport(() -> ContentUriTransfer.prepareContent(
                mActivity.getContentResolver(), content, destination), permissions, null);
    }

    private void startImport(
            final Supplier<ContentImportBatch<?>> prepare,
            final DragAndDropPermissions permissions, final Runnable completed) {
        if (mClosed) {
            release(permissions, completed);
            return;
        }
        final ContentImportBatch<?> request;
        final boolean accepted;
        try {
            // Freeze the sources before queueing, while the UI owns the incoming grant.
            request = prepare.get();
            accepted = mOperations.beginImport(request.size());
        } catch (RuntimeException failure) {
            release(permissions, completed);
            mListener.onImportFinished(null, failure);
            return;
        }
        if (!accepted) {
            release(permissions, completed);
            return;
        }
        mImports.submit(cancelled -> request.run(
                () -> cancelled.getAsBoolean() || mOperations.isImportCancelled(),
                processed -> mOperations.updateImportProgress(processed, request.size())),
                () -> release(permissions, completed)).thenAccept(completion ->
                        onFinished(request.finish(completion.value, completion.failure)));
    }

    private void onFinished(final ContentImportBatch.Result result) {
        mActivity.runOnUiThread(() -> {
            if (!mClosed) {
                mOperations.finishImport();
                mListener.onImportFinished(result, result.firstFailure);
            }
        });
    }

    @Override
    public void close() {
        mClosed = true;
        if (mPickerRequestId != null) {
            AndroidActivityResultStore.discard(mPickerRequestId);
            mPickerRequestId = null;
        }
        mImports.close();
    }

    private static List<Uri> resultUris(final JSONObject data) {
        final List<Uri> uris = new ArrayList<>();
        if (data == null) {
            return uris;
        }
        final String primary = data.optString("dataUri", "");
        if (!primary.isEmpty()) {
            uris.add(Uri.parse(primary));
        }
        final JSONArray clip = data.optJSONArray("clipUris");
        if (clip != null) {
            for (int index = 0; index < clip.length(); index++) {
                final Uri uri = Uri.parse(clip.optString(index, ""));
                if (!Uri.EMPTY.equals(uri) && !uris.contains(uri)) {
                    uris.add(uri);
                }
            }
        }
        return uris;
    }

    private static void release(
            final DragAndDropPermissions permissions, final Runnable completed) {
        try {
            if (permissions != null) {
                permissions.release();
            }
        } finally {
            if (completed != null) {
                completed.run();
            }
        }
    }
}
