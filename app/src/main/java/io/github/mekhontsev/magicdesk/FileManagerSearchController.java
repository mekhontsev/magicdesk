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

    private static final long NO_SEARCH = -1L;
    private static final long PENDING_SEARCH = 0L;

    private final Activity mActivity;
    private final ExecutorService mWorker;
    private final Listener mListener;
    private final IBinder mOwnerToken = new Binder();

    private volatile long mActiveSearchId = NO_SEARCH;
    private volatile long mGeneration;
    private volatile boolean mClosed;

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
        final long generation = ++mGeneration;
        mActiveSearchId = PENDING_SEARCH;
        final IFileSearchCallback callback = callbackFor(generation);
        mWorker.execute(() -> {
            try {
                final long searchId = ShellAccess.startShellFileSearch(
                        rootPath,
                        query,
                        showHidden,
                        maxResults,
                        callback,
                        mOwnerToken);
                if (mClosed || generation != mGeneration) {
                    ShellAccess.cancelShellFileSearch(searchId);
                    return;
                }
                mActivity.runOnUiThread(() -> {
                    if (!mClosed
                            && generation == mGeneration
                            && mActiveSearchId == PENDING_SEARCH) {
                        mActiveSearchId = searchId;
                    }
                });
            } catch (IOException | RuntimeException error) {
                mActivity.runOnUiThread(() -> {
                    if (mClosed || generation != mGeneration) {
                        return;
                    }
                    mActiveSearchId = NO_SEARCH;
                    mListener.onSearchStartFailed(error);
                });
            }
        });
        return true;
    }

    void cancel() {
        mGeneration++;
        final long searchId = mActiveSearchId;
        mActiveSearchId = NO_SEARCH;
        if (searchId <= 0L) {
            return;
        }
        mWorker.execute(() -> {
            try {
                ShellAccess.cancelShellFileSearch(searchId);
            } catch (IOException ignored) {
                // Completion or service shutdown owns cleanup.
            }
        });
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mGeneration++;
        final long searchId = mActiveSearchId;
        mActiveSearchId = NO_SEARCH;
        if (searchId > 0L) {
            try {
                // FileManager shuts down its worker immediately after this
                // method, so lifecycle cleanup must not be queued there.
                ShellAccess.cancelShellFileSearch(searchId);
            } catch (IOException ignored) {
                // A disconnected UserService no longer owns the search.
            }
        }
    }

    private IFileSearchCallback callbackFor(final long generation) {
        return new IFileSearchCallback.Stub() {
            @Override
            public void onBatch(
                    final long searchId,
                    final ShellFileInfo[] matches) {
                mActivity.runOnUiThread(() -> {
                    if (accepts(searchId, generation) && matches != null) {
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
                    if (!accepts(searchId, generation)) {
                        return;
                    }
                    mActiveSearchId = NO_SEARCH;
                    mListener.onSearchFinished(
                            successful, truncated, message);
                });
            }
        };
    }

    private boolean accepts(
            final long searchId,
            final long generation) {
        return !mClosed
                && generation == mGeneration
                && searchId > 0L
                && (mActiveSearchId == PENDING_SEARCH
                        || mActiveSearchId == searchId);
    }
}
