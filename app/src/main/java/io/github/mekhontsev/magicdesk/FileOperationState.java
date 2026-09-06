package io.github.mekhontsev.magicdesk;

/** Request identity survives callbacks arriving before the Binder start reply. */
final class FileOperationState {
    static final long NO_OPERATION = -1L;

    enum State {
        IDLE, STARTING, RUNNING, FINISHED
    }

    static final class Request {
        final long clipboardGeneration;

        private Request(final long clipboardGeneration) {
            this.clipboardGeneration = clipboardGeneration;
        }
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
                final long sequence, final State state,
                final long operationId, final int operation,
                final int completedItems, final int totalItems,
                final String currentPath, final long bytesCompleted,
                final boolean successful, final String message) {
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

    private Request mRequest;
    private Snapshot mSnapshot = new Snapshot(
            0L, State.IDLE, NO_OPERATION, 0, 0, 0, "", 0L, false, "");

    synchronized Snapshot snapshot() {
        return mSnapshot;
    }

    synchronized Request begin(
            final int operation, final int totalItems,
            final long clipboardGeneration) {
        if (mSnapshot.isBusy() || totalItems <= 0) {
            return null;
        }
        mRequest = new Request(clipboardGeneration);
        mSnapshot = new Snapshot(
                mSnapshot.sequence + 1L, State.STARTING, NO_OPERATION,
                operation, 0, totalItems, "", 0L, false, "");
        return mRequest;
    }

    synchronized boolean isRunning(final Request request) {
        return request != null && request == mRequest && mSnapshot.isBusy();
    }

    synchronized boolean started(final Request request, final long id) {
        if (request != mRequest || id <= 0L) {
            return false;
        }
        // A fast operation can finish before start() returns. Keep that result.
        if (!mSnapshot.isBusy()) {
            return mSnapshot.operationId == id;
        }
        return progress(request, id, mSnapshot.completedItems,
                mSnapshot.totalItems, mSnapshot.currentPath,
                mSnapshot.bytesCompleted);
    }

    synchronized boolean progress(
            final Request request, final long id,
            final int completedItems, final int totalItems,
            final String path, final long bytesCompleted) {
        if (!accepts(request, id)) {
            return false;
        }
        mSnapshot = new Snapshot(
                mSnapshot.sequence + 1L, State.RUNNING, id,
                mSnapshot.operation, completedItems, totalItems, path,
                bytesCompleted, false, "");
        return true;
    }

    synchronized boolean finish(
            final Request request, final long id,
            final boolean successful, final String message) {
        if (!accepts(request, id)) {
            return false;
        }
        complete(id, successful, message);
        return true;
    }

    synchronized void fail(final Request request, final String message) {
        if (isRunning(request)) {
            complete(mSnapshot.operationId, false, message);
        }
    }

    synchronized void disconnect(final String message) {
        fail(mRequest, message);
    }

    synchronized Request cancel() {
        if (!mSnapshot.isBusy()) {
            return null;
        }
        final long id = mSnapshot.operationId;
        if (id <= 0L) {
            complete(NO_OPERATION, false, "file operation cancelled");
        }
        return mRequest;
    }

    private boolean accepts(final Request request, final long id) {
        return isRunning(request) && id > 0L
                && (mSnapshot.operationId == NO_OPERATION
                        || mSnapshot.operationId == id);
    }

    private void complete(
            final long id, final boolean successful, final String message) {
        mSnapshot = new Snapshot(
                mSnapshot.sequence + 1L, State.FINISHED, id,
                mSnapshot.operation,
                successful ? mSnapshot.totalItems : mSnapshot.completedItems,
                mSnapshot.totalItems, mSnapshot.currentPath,
                mSnapshot.bytesCompleted, successful, message);
    }
}
