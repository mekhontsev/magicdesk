package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process owner for shell file operations that may outlive a Files window. */
final class FileOperationCenter implements ShellAccess.StateListener {
    interface Listener {
        void onFileOperationStateChanged(FileOperationState.Snapshot snapshot);
    }

    private static final FileOperationCenter INSTANCE = new FileOperationCenter();

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskFileOperationOwner");
                thread.setDaemon(true);
                return thread;
            });
    private final CopyOnWriteArraySet<Listener> mListeners =
            new CopyOnWriteArraySet<>();
    private final IBinder mOwnerToken = new Binder();
    private final FileOperationState mState = new FileOperationState();
    // Accessed only by mWorker; callbacks use the independent request state.
    private FileOperationState.Request mRemoteRequest;
    private ShellFileOperationHandle mRemoteOperation;

    private FileOperationCenter() {
        ShellAccess.addStateListener(this);
    }

    static FileOperationCenter get() {
        return INSTANCE;
    }

    FileOperationState.Snapshot snapshot() {
        return mState.snapshot();
    }

    void addListener(final Listener listener) {
        if (listener != null) {
            mListeners.add(listener);
        }
    }

    void removeListener(final Listener listener) {
        if (listener != null) {
            mListeners.remove(listener);
        }
    }

    boolean start(
            final int operation,
            final List<String> sourcePaths,
            final String destination,
            final long clipboardGeneration) {
        final List<String> paths = sourcePaths == null
                ? List.of() : List.copyOf(sourcePaths);
        final FileOperationState.Request request = mState.begin(
                operation, paths.size(), clipboardGeneration);
        if (request == null) {
            return false;
        }
        notifyListeners();
        mWorker.execute(() -> startRemote(request, operation, paths, destination));
        return true;
    }

    void cancel() {
        final FileOperationState.Request request = mState.cancel();
        notifyListeners();
        if (request != null) {
            mWorker.execute(() -> {
                if (mRemoteRequest == request) {
                    cancelRemote(mRemoteOperation);
                }
            });
        }
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        if (snapshot != null && snapshot.isReady()) {
            return;
        }
        mState.disconnect(snapshot == null || snapshot.error.isEmpty()
                ? "shell access disconnected" : snapshot.error);
        notifyListeners();
    }

    private void startRemote(
            final FileOperationState.Request request,
            final int operation,
            final List<String> paths,
            final String destination) {
        if (!mState.isRunning(request)) {
            return;
        }
        try {
            final ShellFileOperationHandle handle = ShellAccess.startShellFileOperation(
                    operation, paths.toArray(new String[0]), destination,
                    callbackFor(request), mOwnerToken);
            mRemoteRequest = request;
            mRemoteOperation = handle;
            if (!mState.started(request, handle.id)) {
                cancelRemote(handle);
                return;
            }
        } catch (IOException | RuntimeException error) {
            mState.fail(request, ShellAccess.usefulMessage(error));
        }
        notifyListeners();
    }

    private IFileOperationCallback callbackFor(
            final FileOperationState.Request request) {
        return new IFileOperationCallback.Stub() {
            @Override
            public void onProgress(
                    final long operationId,
                    final int completedItems,
                    final int totalItems,
                    final String currentPath,
                    final long bytesCompleted) {
                if (mState.progress(request, operationId, completedItems,
                        totalItems, currentPath, bytesCompleted)) {
                    notifyListeners();
                }
            }

            @Override
            public void onFinished(
                    final long operationId,
                    final boolean successful,
                    final String message) {
                if (!mState.finish(request, operationId, successful, message)) {
                    return;
                }
                if (successful && request.clipboardGeneration >= 0L) {
                    FileClipboardInterop.completeMove(request.clipboardGeneration);
                }
                notifyListeners();
            }
        };
    }

    private static void cancelRemote(final ShellFileOperationHandle handle) {
        try {
            handle.cancel();
        } catch (IOException ignored) {
            // Remote completion or service teardown owns cleanup.
        }
    }

    private void notifyListeners() {
        final FileOperationState.Snapshot snapshot = snapshot();
        mMain.post(() -> {
            if (snapshot.sequence != snapshot().sequence) {
                return;
            }
            for (final Listener listener : mListeners) {
                listener.onFileOperationStateChanged(snapshot);
            }
        });
    }
}
