package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Binder;
import android.os.IBinder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

final class FileManagerSearchController implements AutoCloseable {
    interface Listener {
        void onSearchBatch(List<ShellFileInfo> matches);

        void onSearchFinished(
                boolean successful, boolean truncated, String message);

        void onSearchStartFailed(Throwable error);
    }

    private final Activity mActivity;
    private final ExecutorService mWorker;
    private final Listener mListener;
    private final IBinder mOwnerToken = new Binder();

    private FileSearchRequest mRequest;
    private boolean mClosed;

    FileManagerSearchController(
            final Activity activity,
            final ExecutorService worker,
            final Listener listener) {
        mActivity = activity;
        mWorker = worker;
        mListener = listener;
    }

    boolean start(
            final String rootPath,
            final String query,
            final boolean showHidden,
            final int maxResults) {
        if (mClosed) {
            return false;
        }
        cancel();
        final FileSearchRequest request = new FileSearchRequest();
        mRequest = request;
        final IFileSearchCallback callback = callbackFor(request);
        mWorker.execute(() -> {
            if (!request.isActive()) {
                return;
            }
            try {
                final ShellFileSearchHandle handle = ShellAccess.startShellFileSearch(
                        rootPath,
                        query,
                        showHidden,
                        maxResults,
                        callback,
                        mOwnerToken);
                if (!request.attach(handle)) {
                    cancelRemote(handle);
                }
            } catch (IOException | RuntimeException error) {
                mActivity.runOnUiThread(() -> {
                    if (request.complete()) {
                        mListener.onSearchStartFailed(error);
                    }
                });
            }
        });
        return true;
    }

    void cancel() {
        final ShellFileSearchHandle handle = cancelRequest();
        if (handle != null) {
            cancelRemote(handle);
        }
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        cancel();
    }

    private ShellFileSearchHandle cancelRequest() {
        final FileSearchRequest request = mRequest;
        mRequest = null;
        return request == null ? null : request.cancel();
    }

    private static void cancelRemote(final ShellFileSearchHandle handle) {
        try {
            // Cancellation is a one-way Binder signal. Do not enqueue it on
            // the Activity worker, whose shutdown would discard the signal.
            handle.cancel();
        } catch (IOException ignored) {
            // Completion or service teardown owns cleanup.
        }
    }

    private IFileSearchCallback callbackFor(final FileSearchRequest request) {
        return new IFileSearchCallback.Stub() {
            @Override
            public void onBatch(
                    final long searchId,
                    final ShellFileInfo[] matches) {
                mActivity.runOnUiThread(() -> {
                    if (request.accepts(searchId) && matches != null) {
                        mListener.onSearchBatch(Arrays.asList(matches));
                    }
                });
            }

            @Override
            public void onFinished(
                    final long searchId,
                    final boolean successful,
                    final boolean truncated,
                    final String message) {
                mActivity.runOnUiThread(() -> {
                    if (!request.finish(searchId)) {
                        return;
                    }
                    mListener.onSearchFinished(
                            successful, truncated, message);
                });
            }
        };
    }
}
