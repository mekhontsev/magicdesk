package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process owner for shell file operations that may outlive a Files window. */
final class FileOperationCenter implements ShellAccess.StateListener {
    interface Listener {
        void onFileOperationStateChanged(Snapshot snapshot);
    }

    enum State {
        IDLE,
        STARTING,
        RUNNING,
        FINISHED
    }

    static final class Snapshot {
        final long sequence;
        final State state;
        final long operationId;
        final int operation;
        final int completedItems;
        final int totalItems;
        final String currentPath;
        final long bytesCompleted;
        final boolean successful;
        final String message;

        Snapshot(
                final long sequence,
                final State state,
                final long operationId,
                final int operation,
                final int completedItems,
                final int totalItems,
                final String currentPath,
                final long bytesCompleted,
                final boolean successful,
                final String message) {
            this.sequence = sequence;
            this.state = state;
            this.operationId = operationId;
            this.operation = operation;
            this.completedItems = completedItems;
            this.totalItems = totalItems;
            this.currentPath = currentPath == null ? "" : currentPath;
            this.bytesCompleted = bytesCompleted;
            this.successful = successful;
            this.message = message == null ? "" : message;
        }

        boolean isBusy() {
            return state == State.STARTING || state == State.RUNNING;
        }
    }

    private static final long NO_OPERATION = -1L;
    private static final FileOperationCenter INSTANCE =
            new FileOperationCenter();

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
    private final IFileOperationCallback mCallback =
            new IFileOperationCallback.Stub() {
                @Override
                public void onProgress(
                        final long operationId,
                        final int completedItems,
                        final int totalItems,
                        final String currentPath,
                        final long bytesCompleted) {
                    updateProgress(
                            operationId,
                            completedItems,
                            totalItems,
                            currentPath,
                            bytesCompleted);
                }

                @Override
                public void onFinished(
                        final long operationId,
                        final boolean successful,
                        final String message) {
                    finish(operationId, successful, message);
                }
            };

    private Snapshot mSnapshot = idle(0L);
    private long mSequence;
    private long mRequestGeneration;
    private long mClipboardGeneration = -1L;

    private FileOperationCenter() {
        ShellAccess.addStateListener(this);
    }

    static FileOperationCenter get() {
        return INSTANCE;
    }

    synchronized Snapshot snapshot() {
        return mSnapshot;
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
                ? Collections.emptyList()
                : Collections.unmodifiableList(
                        new ArrayList<>(sourcePaths));
        final long generation;
        synchronized (this) {
            if (mSnapshot.isBusy() || paths.isEmpty()) {
                return false;
            }
            generation = ++mRequestGeneration;
            mClipboardGeneration = clipboardGeneration;
            mSnapshot = new Snapshot(
                    ++mSequence,
                    State.STARTING,
                    NO_OPERATION,
                    operation,
                    0,
                    paths.size(),
                    "",
                    0L,
                    false,
                    "");
        }
        notifyListeners();
        mWorker.execute(() -> startRemote(
                generation, operation, paths, destination));
        return true;
    }

    void cancel() {
        final long operationId;
        final boolean cancelledPending;
        synchronized (this) {
            if (!mSnapshot.isBusy()) {
                return;
            }
            operationId = mSnapshot.operationId;
            cancelledPending = operationId <= 0L;
            if (cancelledPending) {
                mRequestGeneration++;
                mClipboardGeneration = -1L;
                mSnapshot = new Snapshot(
                        ++mSequence,
                        State.FINISHED,
                        NO_OPERATION,
                        mSnapshot.operation,
                        mSnapshot.completedItems,
                        mSnapshot.totalItems,
                        mSnapshot.currentPath,
                        mSnapshot.bytesCompleted,
                        false,
                        "file operation cancelled");
            }
        }
        if (cancelledPending) {
            notifyListeners();
            return;
        }
        mWorker.execute(() -> {
            try {
                ShellAccess.cancelShellFileOperation(operationId);
            } catch (IOException ignored) {
                // Remote completion or service teardown owns cleanup.
            }
        });
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        if (snapshot != null && snapshot.isReady()) {
            return;
        }
        synchronized (this) {
            if (!mSnapshot.isBusy()) {
                return;
            }
            mRequestGeneration++;
            mClipboardGeneration = -1L;
            mSnapshot = new Snapshot(
                    ++mSequence,
                    State.FINISHED,
                    NO_OPERATION,
                    mSnapshot.operation,
                    mSnapshot.completedItems,
                    mSnapshot.totalItems,
                    mSnapshot.currentPath,
                    mSnapshot.bytesCompleted,
                    false,
                    snapshot == null || snapshot.error.isEmpty()
                            ? "shell access disconnected"
                            : snapshot.error);
        }
        notifyListeners();
    }

    private void startRemote(
            final long generation,
            final int operation,
            final List<String> paths,
            final String destination) {
        try {
            final long operationId = ShellAccess.startShellFileOperation(
                    operation,
                    paths.toArray(new String[0]),
                    destination,
                    mCallback,
                    mOwnerToken);
            final boolean cancel;
            synchronized (this) {
                cancel = generation != mRequestGeneration
                        || !mSnapshot.isBusy();
                if (!cancel) {
                    mSnapshot = new Snapshot(
                            ++mSequence,
                            State.RUNNING,
                            operationId,
                            operation,
                            mSnapshot.completedItems,
                            paths.size(),
                            mSnapshot.currentPath,
                            mSnapshot.bytesCompleted,
                            false,
                            "");
                }
            }
            if (cancel) {
                ShellAccess.cancelShellFileOperation(operationId);
                return;
            }
            notifyListeners();
        } catch (IOException | RuntimeException error) {
            synchronized (this) {
                if (generation != mRequestGeneration) {
                    return;
                }
                mClipboardGeneration = -1L;
                mSnapshot = new Snapshot(
                        ++mSequence,
                        State.FINISHED,
                        NO_OPERATION,
                        operation,
                        0,
                        paths.size(),
                        "",
                        0L,
                        false,
                        ShellAccess.usefulMessage(error));
            }
            notifyListeners();
        }
    }

    private void updateProgress(
            final long operationId,
            final int completedItems,
            final int totalItems,
            final String currentPath,
            final long bytesCompleted) {
        synchronized (this) {
            if (!mSnapshot.isBusy()
                    || (mSnapshot.operationId > 0L
                    && mSnapshot.operationId != operationId)) {
                return;
            }
            mSnapshot = new Snapshot(
                    ++mSequence,
                    State.RUNNING,
                    operationId,
                    mSnapshot.operation,
                    completedItems,
                    totalItems,
                    currentPath,
                    bytesCompleted,
                    false,
                    "");
        }
        notifyListeners();
    }

    private void finish(
            final long operationId,
            final boolean successful,
            final String message) {
        final long clipboardGeneration;
        synchronized (this) {
            if (!mSnapshot.isBusy()
                    || (mSnapshot.operationId > 0L
                    && mSnapshot.operationId != operationId)) {
                return;
            }
            clipboardGeneration = successful
                    ? mClipboardGeneration : -1L;
            mClipboardGeneration = -1L;
            mSnapshot = new Snapshot(
                    ++mSequence,
                    State.FINISHED,
                    operationId,
                    mSnapshot.operation,
                    mSnapshot.totalItems,
                    mSnapshot.totalItems,
                    mSnapshot.currentPath,
                    mSnapshot.bytesCompleted,
                    successful,
                    message);
        }
        if (clipboardGeneration >= 0L) {
            FileManagerClipboard.clearIfGeneration(clipboardGeneration);
        }
        notifyListeners();
    }

    private void notifyListeners() {
        final Snapshot snapshot = snapshot();
        mMain.post(() -> {
            if (snapshot.sequence != snapshot().sequence) {
                return;
            }
            for (final Listener listener : mListeners) {
                listener.onFileOperationStateChanged(snapshot);
            }
        });
    }

    private static Snapshot idle(final long sequence) {
        return new Snapshot(
                sequence,
                State.IDLE,
                NO_OPERATION,
                0,
                0,
                0,
                "",
                0L,
                false,
                "");
    }
}
